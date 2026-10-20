package maja

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

const (
	defaultTokenURL = "https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token"
	defaultBaseURL  = "https://billing.maja.id"
	tokenTTL        = 5 * time.Minute
)

type Config struct {
	TokenURL    string
	BaseURL     string
	Username    string
	Password    string
	ClientID    string
	ClientSecret string
	HTTPClient  *http.Client
}

type Client struct {
	httpClient *http.Client
	tokenURL   string
	baseURL    string
	username   string
	password   string
	clientID   string
	clientSecret string

	tokenMu  sync.Mutex
	token    string
	tokenAt  time.Time
}

type InvoiceItem struct {
	Description string  `json:"description"`
	Qty         int     `json:"qty"`
	UnitPrice   float64 `json:"unitPrice"`
	Amount      float64 `json:"amount"`
}

type RegisterRequest struct {
	Name          string        `json:"name"`
	Email         string        `json:"email"`
	Phone         string        `json:"phone,omitempty"`
	Number        string        `json:"number"`
	Amount        float64       `json:"amount"`
	ActiveDate    string        `json:"activeDate"`
	Date          string        `json:"date"`
	InactiveDate  string        `json:"inactiveDate,omitempty"`
	Items         []InvoiceItem `json:"items"`
	Attribute1    string        `json:"attribute1,omitempty"`
	Attribute2    string        `json:"attribute2"`
	Attribute3    string        `json:"attribute3,omitempty"`
	PaymentMethod string        `json:"paymentMethod"`
}

type RegisterData struct {
	ID           *int64          `json:"id,omitempty"`
	Number       json.RawMessage `json:"number,omitempty"`
	VA           string          `json:"va,omitempty"`
	Amount       *float64        `json:"amount,omitempty"`
	Paid         *bool           `json:"paid,omitempty"`
	InactiveDate *string         `json:"inactiveDate,omitempty"`
}

type InquiryData struct {
	Paid            *bool    `json:"paid,omitempty"`
	Amount          *float64 `json:"amount,omitempty"`
	RemainingAmount *float64 `json:"remainingAmount,omitempty"`
}

type apiResponse struct {
	Code    string          `json:"code"`
	Message string          `json:"message"`
	Data    json.RawMessage `json:"data"`
}

type tokenResponse struct {
	AccessToken string `json:"access_token"`
}

func NewClient(config Config) *Client {
	if config.TokenURL == "" {
		config.TokenURL = defaultTokenURL
	}
	if config.BaseURL == "" {
		config.BaseURL = defaultBaseURL
	}
	if config.HTTPClient == nil {
		config.HTTPClient = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{
		httpClient:  config.HTTPClient,
		tokenURL:    config.TokenURL,
		baseURL:     strings.TrimRight(config.BaseURL, "/"),
		username:    config.Username,
		password:    config.Password,
		clientID:    config.ClientID,
		clientSecret: config.ClientSecret,
	}
}

func NewClientFromEnv() *Client {
	return NewClient(Config{
		TokenURL:     os.Getenv("MAJA_TOKEN_URL"),
		BaseURL:      os.Getenv("MAJA_BASE_URL"),
		Username:     os.Getenv("MAJA_USERNAME"),
		Password:     os.Getenv("MAJA_PASSWORD"),
		ClientID:     os.Getenv("MAJA_CLIENT_ID"),
		ClientSecret: os.Getenv("MAJA_CLIENT_SECRET"),
	})
}

func (c *Client) Configured() bool {
	return c.username != "" && c.password != "" && c.clientID != "" && c.clientSecret != ""
}

func (c *Client) Register(ctx context.Context, request RegisterRequest) (*RegisterData, error) {
	var response apiResponse
	if err := c.postJSON(ctx, "/api/v2/register", request, &response); err != nil {
		return nil, err
	}
	if response.Code != "00" {
		return nil, fmt.Errorf("MAJA register ditolak (%s): %s", fallback(response.Code, "-"), response.Message)
	}
	if len(response.Data) == 0 || string(response.Data) == "null" {
		return nil, fmt.Errorf("MAJA register tanpa data")
	}
	var data RegisterData
	if err := json.Unmarshal(response.Data, &data); err != nil {
		return nil, fmt.Errorf("respons MAJA register tidak valid: %w", err)
	}
	return &data, nil
}

func (c *Client) Inquiry(ctx context.Context, va, invoiceNumber string) (*InquiryData, error) {
	var response apiResponse
	if err := c.postJSON(ctx, "/api/v2/inquiry", map[string]string{"va": va, "invoiceNumber": invoiceNumber}, &response); err != nil {
		return nil, err
	}
	if len(response.Data) == 0 || string(response.Data) == "null" {
		return nil, fmt.Errorf("MAJA inquiry gagal (%s): %s", fallback(response.Code, "-"), response.Message)
	}
	var data InquiryData
	if err := json.Unmarshal(response.Data, &data); err != nil {
		return nil, fmt.Errorf("respons MAJA inquiry tidak valid: %w", err)
	}
	return &data, nil
}

func (c *Client) Cancel(ctx context.Context, va, invoiceNumber string) error {
	var response apiResponse
	if err := c.postJSON(ctx, "/api/v2/cancel", map[string]string{"va": va, "invoiceNumber": invoiceNumber}, &response); err != nil {
		return err
	}
	if response.Code == "00" {
		return nil
	}
	message := strings.ToLower(response.Message)
	for _, phrase := range []string{"sudah dibatalkan", "invalid", "tidak ditemukan"} {
		if strings.Contains(message, phrase) {
			return nil
		}
	}
	return fmt.Errorf("MAJA cancel ditolak (%s): %s", fallback(response.Code, "-"), message)
}

func (c *Client) accessToken(ctx context.Context) (string, error) {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	if c.token != "" && time.Since(c.tokenAt) < tokenTTL {
		return c.token, nil
	}
	if !c.Configured() {
		return "", fmt.Errorf("MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)")
	}
	form := url.Values{
		"grant_type":   {"password"},
		"username":     {c.username},
		"password":     {c.password},
		"client_id":    {c.clientID},
		"client_secret": {c.clientSecret},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("membuat MAJA token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("tidak dapat menghubungi MAJA token endpoint: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return "", fmt.Errorf("MAJA token gagal (HTTP %d)", resp.StatusCode)
	}
	var payload tokenResponse
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return "", fmt.Errorf("respons MAJA token bukan JSON: %w", err)
	}
	if payload.AccessToken == "" {
		return "", fmt.Errorf("respons MAJA token tanpa access_token")
	}
	c.token = payload.AccessToken
	c.tokenAt = time.Now()
	return c.token, nil
}

func (c *Client) postJSON(ctx context.Context, path string, body, out any) error {
	token, err := c.accessToken(ctx)
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(body)
	if err != nil {
		return fmt.Errorf("encode MAJA request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, bytes.NewReader(encoded))
	if err != nil {
		return fmt.Errorf("membuat MAJA request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("tidak dapat menghubungi MAJA: %w", err)
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return fmt.Errorf("membaca respons MAJA: %w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		message := strings.TrimSpace(string(data))
		if message == "" {
			message = resp.Status
		}
		return fmt.Errorf("MAJA %s gagal (HTTP %d): %s", path, resp.StatusCode, message)
	}
	if err := json.Unmarshal(data, out); err != nil {
		return fmt.Errorf("respons MAJA %s bukan JSON: %w", path, err)
	}
	return nil
}

func fallback(value, defaultValue string) string {
	if value == "" {
		return defaultValue
	}
	return value
}