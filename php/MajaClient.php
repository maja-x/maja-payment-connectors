<?php
declare(strict_types=1);

namespace Maja\Payment;

use JsonException;
use RuntimeException;

class MajaException extends RuntimeException
{
}

class MajaServiceUnavailableException extends MajaException
{
}

class MajaBadRequestException extends MajaException
{
}

final class MajaClient
{
    private const TOKEN_TTL = 300;
    private const DEFAULT_TOKEN_URL = 'https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token';
    private const DEFAULT_BASE_URL = 'https://billing.maja.id';

    private string $tokenUrl;
    private string $baseUrl;
    private ?string $username;
    private ?string $password;
    private ?string $clientId;
    private ?string $clientSecret;
    private ?string $token = null;
    private int $tokenAt = 0;
    private int $timeout;

    /** @param array<string, mixed> $config */
    public function __construct(array $config = [])
    {
        $this->tokenUrl = (string) ($config['MAJA_TOKEN_URL'] ?? getenv('MAJA_TOKEN_URL') ?: self::DEFAULT_TOKEN_URL);
        $this->baseUrl = rtrim((string) ($config['MAJA_BASE_URL'] ?? getenv('MAJA_BASE_URL') ?: self::DEFAULT_BASE_URL), '/');
        $this->username = $this->value($config, 'MAJA_USERNAME');
        $this->password = $this->value($config, 'MAJA_PASSWORD');
        $this->clientId = $this->value($config, 'MAJA_CLIENT_ID');
        $this->clientSecret = $this->value($config, 'MAJA_CLIENT_SECRET');
        $this->timeout = (int) ($config['MAJA_TIMEOUT'] ?? getenv('MAJA_TIMEOUT') ?: 30);
    }

    /** @param array<string, mixed> $request
     *  @return array<string, mixed>
     */
    public function register(array $request): array
    {
        $body = $this->postJson('/api/v2/register', $request);
        if (($body['code'] ?? null) !== '00') {
            throw new MajaBadRequestException(sprintf(
                'MAJA register ditolak (%s): %s',
                $body['code'] ?? '-',
                $body['message'] ?? ''
            ));
        }
        if (!isset($body['data']) || !is_array($body['data'])) {
            throw new MajaServiceUnavailableException('MAJA register tanpa data');
        }
        return $body['data'];
    }

    /** @return array<string, mixed> */
    public function inquiry(string $va, string $invoiceNumber): array
    {
        $body = $this->postJson('/api/v2/inquiry', ['va' => $va, 'invoiceNumber' => $invoiceNumber]);
        if (!isset($body['data']) || !is_array($body['data'])) {
            throw new MajaBadRequestException(sprintf(
                'MAJA inquiry gagal (%s): %s',
                $body['code'] ?? '-',
                $body['message'] ?? ''
            ));
        }
        return $body['data'];
    }

    public function cancel(string $va, string $invoiceNumber): void
    {
        $body = $this->postJson('/api/v2/cancel', ['va' => $va, 'invoiceNumber' => $invoiceNumber]);
        if (($body['code'] ?? null) === '00') {
            return;
        }
        $message = strtolower((string) ($body['message'] ?? ''));
        foreach (['sudah dibatalkan', 'invalid', 'tidak ditemukan'] as $phrase) {
            if (str_contains($message, $phrase)) {
                return;
            }
        }
        throw new MajaBadRequestException(sprintf('MAJA cancel ditolak (%s): %s', $body['code'] ?? '-', $message));
    }

    private function accessToken(): string
    {
        if ($this->token !== null && time() - $this->tokenAt < self::TOKEN_TTL) {
            return $this->token;
        }
        foreach ([$this->username, $this->password, $this->clientId, $this->clientSecret] as $credential) {
            if ($credential === null || $credential === '') {
                throw new MajaServiceUnavailableException(
                    'MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)'
                );
            }
        }

        $response = $this->send(
            $this->tokenUrl,
            'POST',
            ['Content-Type: application/x-www-form-urlencoded'],
            http_build_query([
                'grant_type' => 'password',
                'username' => $this->username,
                'password' => $this->password,
                'client_id' => $this->clientId,
                'client_secret' => $this->clientSecret,
            ], '', '&', PHP_QUERY_RFC3986)
        );
        if ($response['status'] < 200 || $response['status'] >= 300) {
            throw new MajaServiceUnavailableException(sprintf('MAJA token gagal (HTTP %d)', $response['status']));
        }
        $payload = $this->decodeJson($response['body'], 'token');
        if (!isset($payload['access_token']) || !is_string($payload['access_token'])) {
            throw new MajaServiceUnavailableException('Respons MAJA token tanpa access_token');
        }
        $this->token = $payload['access_token'];
        $this->tokenAt = time();
        return $this->token;
    }

    /** @param array<string, mixed> $body
     *  @return array<string, mixed>
     */
    private function postJson(string $path, array $body): array
    {
        $response = $this->send(
            $this->baseUrl . $path,
            'POST',
            ['Content-Type: application/json', 'Authorization: Bearer ' . $this->accessToken()],
            json_encode($body, JSON_THROW_ON_ERROR)
        );
        if ($response['status'] < 200 || $response['status'] >= 300) {
            throw new MajaServiceUnavailableException(sprintf(
                'MAJA %s gagal (HTTP %d): %s',
                $path,
                $response['status'],
                $response['body'] !== '' ? $response['body'] : 'HTTP error'
            ));
        }
        return $this->decodeJson($response['body'], $path);
    }

    /** @param list<string> $headers
     *  @return array{status: int, body: string}
     */
    private function send(string $url, string $method, array $headers, string $body): array
    {
        $context = stream_context_create(['http' => [
            'method' => $method,
            'header' => implode("\r\n", $headers),
            'content' => $body,
            'timeout' => $this->timeout,
            'ignore_errors' => true,
        ]]);
        $responseBody = @file_get_contents($url, false, $context);
        if ($responseBody === false) {
            throw new MajaServiceUnavailableException('Tidak dapat menghubungi MAJA');
        }
        $statusLine = $http_response_header[0] ?? '';
        preg_match('/\s(\d{3})\s/', $statusLine, $matches);
        return ['status' => (int) ($matches[1] ?? 0), 'body' => $responseBody];
    }

    /** @return array<string, mixed> */
    private function decodeJson(string $body, string $operation): array
    {
        try {
            $decoded = json_decode($body, false, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new MajaServiceUnavailableException("Respons MAJA {$operation} bukan JSON", 0, $error);
        }
        if (!$decoded instanceof \stdClass) {
            throw new MajaServiceUnavailableException("Respons MAJA {$operation} bukan objek JSON");
        }
        return $this->toArray($decoded);
    }

    /** @return array<string, mixed> */
    private function toArray(\stdClass $object): array
    {
        $result = [];
        foreach ($object as $key => $value) {
            if ($value instanceof \stdClass) {
                $result[$key] = $this->toArray($value);
            } elseif (is_array($value)) {
                $result[$key] = array_map(
                    fn ($item) => $item instanceof \stdClass ? $this->toArray($item) : $item,
                    $value
                );
            } else {
                $result[$key] = $value;
            }
        }
        return $result;
    }

    /** @param array<string, mixed> $config */
    private function value(array $config, string $key): ?string
    {
        $value = $config[$key] ?? getenv($key);
        return $value === false || $value === null ? null : (string) $value;
    }
}