import {
  BadRequestException,
  Injectable,
  Logger,
  ServiceUnavailableException,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

/**
 * Client H2H MAJA (spec v2.2) `.
 *
 * Autentikasi memakai OpenID *Resource Owner Password Grant*: satu token
 * di-cache 5 menit dan dipakai ulang untuk register / inquiry / cancel.
 * Bila kredensial belum diisi, seluruh endpoint pembayaran mengembalikan 503
 */

export interface MajaInvoiceItem {
  description: string;
  qty: number;
  unitPrice: number;
  amount: number;
}

export interface MajaRegisterRequest {
  name: string;
  email: string;
  phone?: string;
  /** Nomor invoice unik — dipakai sebagai anchor rekonsiliasi webhook. */
  number: string;
  amount: number;
  /** Tanggal invoice mulai aktif (`YYYY-MM-DD HH:mm:ss`). */
  activeDate: string;
  /** Tanggal invoice (`YYYY-MM-DD HH:mm:ss`). */
  date: string;
  /** Tanggal kedaluwarsa (`YYYY-MM-DD HH:mm:ss`). */
  inactiveDate?: string;
  items: MajaInvoiceItem[];
  /**
   * Atribut kustom MAJA (spec v2.2 §5c).
   * `attribute2` WAJIB (mandatory)
   */
  attribute1?: string;
  attribute2: string;
  attribute3?: string;
  paymentMethod: string;
}

export interface MajaRegisterData {
  id?: number | string;
  number?: number | string;
  va?: string;
  amount?: number;
  paid?: boolean;
  inactiveDate?: string | null;
}

export interface MajaInquiryData {
  paid?: boolean;
  amount?: number;
  remainingAmount?: number;
}

/** Token dianggap masih valid selama 5 menit (mengikuti jr-payment). */
const TOKEN_TTL_MS = 5 * 60 * 1000;

@Injectable()
export class MajaService {
  private readonly logger = new Logger(MajaService.name);
  private token: { value: string; at: number } | null = null;

  constructor(private readonly config: ConfigService) {}

  private get tokenUrl(): string {
    return this.config.get<string>(
      'MAJA_TOKEN_URL',
      'https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token',
    );
  }

  private get baseUrl(): string {
    return this.config.get<string>('MAJA_BASE_URL', 'https://billing.maja.id').replace(/\/+$/, '');
  }

  private get username(): string | undefined {
    return this.config.get<string>('MAJA_USERNAME') || undefined;
  }

  private get password(): string | undefined {
    return this.config.get<string>('MAJA_PASSWORD') || undefined;
  }

  private get clientId(): string | undefined {
    return this.config.get<string>('MAJA_CLIENT_ID') || undefined;
  }

  private get clientSecret(): string | undefined {
    return this.config.get<string>('MAJA_CLIENT_SECRET') || undefined;
  }

  get customerEmail(): string {
    return this.config.get<string>('MAJA_CUSTOMER_EMAIL', 'peserta@maja.local');
  }

  /** Kredensial H2H lengkap atau tidak. */
  isConfigured(): boolean {
    return Boolean(this.username && this.password && this.clientId && this.clientSecret);
  }

  /** Cetak payload request ke log. Default mati karena isinya data pribadi. */
  private get debugPayload(): boolean {
    return this.config.get<string>('MAJA_DEBUG_PAYLOAD', '0') === '1';
  }

  private assertConfigured(): void {
    if (!this.isConfigured()) {
      throw new ServiceUnavailableException(
        'MAJA belum dikonfigurasi (isi MAJA_USERNAME/MAJA_PASSWORD/MAJA_CLIENT_ID/MAJA_CLIENT_SECRET)',
      );
    }
  }

  /** Ambil (dan cache) access token MAJA. */
  private async accessToken(): Promise<string> {
    this.assertConfigured();

    if (this.token && Date.now() - this.token.at < TOKEN_TTL_MS) {
      return this.token.value;
    }

    const body = new URLSearchParams({
      grant_type: 'password',
      username: this.username as string,
      password: this.password as string,
      client_id: this.clientId as string,
      client_secret: this.clientSecret as string,
    });

    let response: Response;
    try {
      response = await fetch(this.tokenUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body,
      });
    } catch (error) {
      throw new ServiceUnavailableException(
        `Tidak dapat menghubungi MAJA token endpoint: ${messageOf(error)}`,
      );
    }

    if (!response.ok) {
      throw new ServiceUnavailableException(`MAJA token gagal (HTTP ${response.status})`);
    }

    const payload = (await response.json()) as { access_token?: string };
    if (!payload.access_token) {
      throw new ServiceUnavailableException('Respons MAJA token tanpa access_token');
    }

    this.token = { value: payload.access_token, at: Date.now() };
    return this.token.value;
  }

  private async postJson(path: string, body: unknown): Promise<Record<string, any>> {
    const token = await this.accessToken();
    const payload = JSON.stringify(body);

    if (this.debugPayload) {
      this.logger.warn(`MAJA ${path} request: ${payload}`);
    }

    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: payload,
      });
    } catch (error) {
      throw new ServiceUnavailableException(`Tidak dapat menghubungi MAJA: ${messageOf(error)}`);
    }

    const text = await response.text();

    if (!response.ok) {
      this.logger.error(`MAJA ${path} HTTP ${response.status}: ${text || response.statusText}`);
      throw new ServiceUnavailableException(
        `MAJA ${path} gagal (HTTP ${response.status}): ${text || response.statusText}`,
      );
    }

    try {
      return JSON.parse(text) as Record<string, any>;
    } catch {
      throw new ServiceUnavailableException(`Respons MAJA ${path} bukan JSON`);
    }
  }

  /**
   * Registrasi invoice → MAJA membuat Virtual Account.
   * `code === '00'` berarti sukses (spec v2.2 §5).
   */
  async register(request: MajaRegisterRequest): Promise<MajaRegisterData> {
    const body = await this.postJson('/api/v2/register', request);

    if (body.code !== '00') {
      throw new BadRequestException(
        `MAJA register ditolak (${body.code ?? '-'}): ${body.message ?? ''}`.trim(),
      );
    }

    const data = body.data as MajaRegisterData | undefined;
    if (!data) {
      throw new ServiceUnavailableException('MAJA register tanpa data');
    }

    this.logger.log(
      `MAJA register sukses — invoice ${request.number}, VA ${data.va ?? '-'}`,
    );

    return data;
  }

  /** Inquiry by VA + nomor invoice. `data.paid === true` berarti sudah lunas. */
  async inquiry(va: string, invoiceNumber: string): Promise<MajaInquiryData> {
    const body = await this.postJson('/api/v2/inquiry', { va, invoiceNumber });

    const data = body.data as MajaInquiryData | undefined;
    if (!data) {
      throw new BadRequestException(
        `MAJA inquiry gagal (${body.code ?? '-'}): ${body.message ?? ''}`.trim(),
      );
    }

    return data;
  }

  /**
   * Batalkan invoice. Idempoten: respons "sudah dibatalkan / invalid /
   * tidak ditemukan" tetap dianggap sukses (spec v2.2 §8).
   */
  async cancel(va: string, invoiceNumber: string): Promise<void> {
    const body = await this.postJson('/api/v2/cancel', { va, invoiceNumber });

    if (body.code === '00') return;

    const message = String(body.message ?? '').toLowerCase();
    const idempotent = ['sudah dibatalkan', 'invalid', 'tidak ditemukan'].some((keyword) =>
      message.includes(keyword),
    );
    if (idempotent) return;

    throw new BadRequestException(
      `MAJA cancel ditolak (${body.code ?? '-'}): ${message}`.trim(),
    );
  }
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
