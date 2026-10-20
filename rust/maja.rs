use std::sync::Arc;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::Mutex;

use crate::{config::Config, error::AppError};

#[derive(Clone)]
pub struct MajaClient {
    http: reqwest::Client,
    pub token_url: String,
    pub base_url: String,
    username: Option<String>,
    password: Option<String>,
    client_id: Option<String>,
    client_secret: Option<String>,
    pub customer_email: String,
    token: Arc<Mutex<Option<(String, Instant)>>>,
}

impl MajaClient {
    pub fn from_config(config: &Config) -> Self {
        MajaClient {
            http: reqwest::Client::new(),
            token_url: config.maja_token_url.clone(),
            base_url: config.maja_base_url.clone(),
            username: config.maja_username.clone(),
            password: config.maja_password.clone(),
            client_id: config.maja_client_id.clone(),
            client_secret: config.maja_client_secret.clone(),
            customer_email: config.maja_customer_email.clone(),
            token: Arc::new(Mutex::new(None)),
        }
    }

    /// Whether all H2H credentials are present. When false, payment endpoints
    /// fail with a clear 503 instead of silently falling back to a mock.
    pub fn configured(&self) -> bool {
        [&self.username, &self.password, &self.client_id, &self.client_secret]
            .iter()
            .all(|v| v.as_deref().map_or(false, |s| !s.is_empty()))
    }

    /// Obtain (and cache) a MAJA access token via Resource Owner Password Grant.
    async fn access_token(&self) -> Result<String, AppError> {
        let mut guard = self.token.lock().await;
        if let Some((token, at)) = guard.as_ref() {
            if at.elapsed() < Duration::from_secs(300) {
                return Ok(token.clone());
            }
        }
        let Some((username, password, client_id, client_secret)) = (|| {
            Some((
                self.username.as_deref()?,
                self.password.as_deref()?,
                self.client_id.as_deref()?,
                self.client_secret.as_deref()?,
            ))
        })()
        else {
            return Err(AppError::ServiceUnavailable(
                "MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)"
                    .to_string(),
            ));
        };

        let resp = self
            .http
            .post(&self.token_url)
            .form(&[
                ("grant_type", "password"),
                ("username", username),
                ("password", password),
                ("client_id", client_id),
                ("client_secret", client_secret),
            ])
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(AppError::ServiceUnavailable(format!(
                "MAJA token gagal (HTTP {})",
                resp.status()
            )));
        }
        let body: Value = resp.json().await?;
        let token = body
            .get("access_token")
            .and_then(|v| v.as_str())
            .ok_or_else(|| AppError::Internal(anyhow::anyhow!("MAJA token response tanpa access_token")))?
            .to_string();
        *guard = Some((token.clone(), Instant::now()));
        Ok(token)
    }

    /// Register an invoice → creates a VA. `code == "00"` is success (spec v2.2).
    pub async fn register(&self, req: &RegisterRequest) -> Result<RegisterData, AppError> {
        let token = self.access_token().await?;
        let resp = self
            .http
            .post(format!("{}/api/v2/register", self.base_url))
            .bearer_auth(token)
            .json(req)
            .send()
            .await?;
        if !resp.status().is_success() {
            let text = resp.text().await.unwrap_or_default();
            return Err(AppError::ServiceUnavailable(format!(
                "MAJA register gagal (HTTP): {text}"
            )));
        }
        let body: RegisterResponse = resp.json().await?;
        if body.code != "00" {
            return Err(AppError::bad_request(format!(
                "MAJA register ditolak ({}): {}",
                body.code,
                body.message.unwrap_or_default()
            )));
        }
        body.data
            .ok_or_else(|| AppError::Internal(anyhow::anyhow!("MAJA register tanpa data")))
    }

    /// Inquiry by VA + invoice number. `data.paid == true` means the invoice is paid.
    pub async fn inquiry(&self, va: &str, invoice_number: &str) -> Result<InquiryData, AppError> {
        let token = self.access_token().await?;
        let resp = self
            .http
            .post(format!("{}/api/v2/inquiry", self.base_url))
            .bearer_auth(token)
            .json(&json!({ "va": va, "invoiceNumber": invoice_number }))
            .send()
            .await?;
        let body: InquiryResponse = resp.json().await?;
        body.data.ok_or_else(|| {
            AppError::bad_request(format!(
                "MAJA inquiry gagal ({}): {}",
                body.code,
                body.message.unwrap_or_default()
            ))
        })
    }

    /// Cancel an invoice. Idempotent: already-cancelled / invalid invoice
    /// responses are treated as success (state-machine §3).
    pub async fn cancel(&self, va: &str, invoice_number: &str) -> Result<(), AppError> {
        let token = self.access_token().await?;
        let resp = self
            .http
            .post(format!("{}/api/v2/cancel", self.base_url))
            .bearer_auth(token)
            .json(&json!({ "va": va, "invoiceNumber": invoice_number }))
            .send()
            .await?;
        if !resp.status().is_success() {
            return Err(AppError::ServiceUnavailable(format!(
                "MAJA cancel gagal (HTTP {})",
                resp.status()
            )));
        }
        let body: CancelResponse = resp.json().await?;
        if body.code == "00" {
            return Ok(());
        }
        let msg = body.message.unwrap_or_default().to_lowercase();
        let idempotent = ["sudah dibatalkan", "invalid", "tidak ditemukan"]
            .iter()
            .any(|k| msg.contains(k));
        if idempotent {
            return Ok(());
        }
        Err(AppError::bad_request(format!(
            "MAJA cancel ditolak ({}): {}",
            body.code, msg
        )))
    }
}

// ---------------------------------------------------------------------------
// Request / response types (spec v2.2)
// ---------------------------------------------------------------------------

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterRequest {
    pub name: String,
    pub email: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub phone: Option<String>,
    /// Unique invoice number — equals the booking code (reconciliation anchor).
    pub number: String,
    pub amount: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub inactive_date: Option<String>,
    pub items: Vec<InvoiceItem>,
    pub payment_method: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InvoiceItem {
    pub description: String,
    pub qty: i32,
    pub unit_price: f64,
    pub amount: f64,
}

#[derive(Debug, Deserialize)]
pub struct RegisterResponse {
    pub code: String,
    #[serde(default)]
    pub message: Option<String>,
    #[serde(default)]
    pub success: Option<bool>,
    #[serde(default)]
    pub data: Option<RegisterData>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RegisterData {
    #[serde(default)]
    pub id: Option<i64>,
    /// MAJA may echo the invoice number as a number or a string.
    #[serde(default)]
    pub number: Value,
    #[serde(default)]
    pub va: Option<String>,
    #[serde(default)]
    pub inactive_date: Option<String>,
    #[serde(default)]
    pub amount: Option<f64>,
    #[serde(default)]
    pub paid: Option<bool>,
}

#[derive(Debug, Deserialize)]
pub struct InquiryResponse {
    pub code: String,
    #[serde(default)]
    pub message: Option<String>,
    #[serde(default)]
    pub data: Option<InquiryData>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InquiryData {
    #[serde(default)]
    pub paid: Option<bool>,
    #[serde(default)]
    pub remaining_amount: Option<f64>,
    #[serde(default)]
    pub amount: Option<f64>,
}

#[derive(Debug, Deserialize)]
pub struct CancelResponse {
    pub code: String,
    #[serde(default)]
    pub message: Option<String>,
}