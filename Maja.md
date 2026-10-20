 
# MAJA API Documentation
**Version:** 2.2

## 1. Overview
Spesifikasi ini menjelaskan spesifikasi API dan Alur Transaksi yang akan digunakan oleh Merchant untuk mendaftarkan invoice dan metode pembayaran yang akan digunakan.

## 2. Requirement
Anda harus memiliki username, password, client id, dan client secret untuk melakukan autentikasi dengan resource server kami. Resource server menggunakan standar autentikasi OpenID (Resource owner password grant).

## 3. Transaction Flow
1. **Customer checkout**: Pelanggan melakukan checkout.
2. **Merchant Register the Invoice**: Merchant mendaftarkan invoice.
3. **Invoice saved in MAJA Billing System**: Invoice disimpan di sistem billing MAJA.
4. **Customer Pay the Invoice**: Pelanggan membayar invoice.
5. **MAJA Send payment notification to merchant**: MAJA mengirimkan notifikasi pembayaran ke merchant.
6. **Finish**: Selesai.

## 4. Authentication
**Authentication type:** OpenID  
**Grant type:** password (Resource Owner Password Grant)

### a) Get Access Token
Anda dapat memperoleh access token dengan melakukan autentikasi ke Authorization Service kami. Contoh:

```bash
curl -d "grant_type=password" \
-d "client_id=<YOUR CLIENT ID>" \
-d "client_secret=<YOUR CLIENT SECRET>" \
-d "username=<YOUR USERNAME> " \
-d "password=<YOUR PASSWORD>" \
https://authorization.url
```

### b) Add Authorization Header
Silakan tambahkan header Authorization dengan format berikut: `Bearer <ACCESS TOKEN>`  
**URL:** `https://account.makaramas.com/auth/realms/maja/protocol/openid-connect/token`

### c) IP Whitelist
Silakan tambahkan IP di bawah ini:
- 108.136.89.40

*Hubungi kami untuk informasi kredensial klien.*

## 5. Invoice Registration
### a) Flow
Merchant mengirimkan registrasi invoice ke Maja, Maja membuat invoice, dan kemudian memberikan respon invoice kembali ke Merchant.

### b) End Point
**HTTP Method:** POST  
**URL:** `https://billing.maja.id/api/v2/register`

### c) Post Data (JSON)
| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| name | String | Yes | Nama pelanggan |
| email | String | Yes | Email pelanggan |
| phone | String | No | Telepon pelanggan |
| address | String | No | Alamat pelanggan |
| number | String | No | Nomor invoice unik. Jika tidak ditentukan, akan dibuat oleh sistem |
| attribute1 | String | No | Atribut kustom |
| attribute2 | String | No | Atribut kustom |
| attribute3 | String | No | Atribut kustom |
| attribute4 | String | No | Atribut kustom |
| attribute5 | String | No | Atribut kustom |
| amount | Double | Yes | Jumlah invoice |
| activeDate | String | No | Tanggal aktif (YYYY-MM-DD HH:mm:ss) |
| inactiveDate | String | No | Tanggal tidak aktif (YYYY-MM-DD HH:mm:ss) |
| date | String | No | Tanggal invoice |
| items | Array | Yes | Item (lihat invoice items) |
| attributes | Array | No | Atribut tambahan (lihat Invoice Attributes) |
| paymentMethod | String | Yes | Metode pembayaran |


**Invoice Items**
| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| description | String | Yes | Deskripsi |
| qty | Integer | Yes | Kuantitas |
| unitPrice | Double | Yes | Harga satuan |
| amount | Double | Yes | Total jumlah |


**Invoice Attributes**
| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| name | String | Yes | Nama atribut |
| value | String | Yes | Nilai atribut |


### d) Example request
```json
{
  "date": "2021-02-28",
  "amount": 100000,
  "name": "Alfiyah",
  "email": "alfiyah@sebuahdomain.com",
  "attribute1": "Fasilkom",
  "attribute2": "Manajemen Sistem Informasi",
  "items": [
    {
      "description": "Registration",
      "unitPrice": 100000,
      "qty": 1,
      "amount": 100000
    }
  ],
  "paymentMethod": "bni"
}
```


### e) Example response
```json
{
  "timestamp": "2021-02-28T04:45:19.104+00:00",
  "code": "00",
  "message": "Sukses",
  "success": true,
  "data": {
    "id": 18825,
    "date": "2021-02-28T00:00:00.000+00:00",
    "number": 2501,
    "activeDate": null,
    "inactiveDate": null,
    "currency": "IDR",
    "amount": 100000.0,
    "paid": false,
    "sequenceNumber": 1,
    "openPayment": false,
    "va": "88081234567",
    "name": "Alfiyah",
    "email": "alfiyah@sebuahdomain.com",
    "phone": "081222888215",
    "address": null,
    "attributes": [],
    "items": [
      {
        "id": 18826,
        "description": "Registration",
        "unitPrice": 100000.0,
        "qty": 1,
        "amount": 100000.0
      }
    ],
    "attribute1": "Fasilkom",
    "attribute2": "Manajemen Sistem Informasi",
    "attribute3": "",
    "attribute4": "",
    "attribute5": "",
    "paymentMethod": "bni"
  }
}
```


## 6. Payment Notification
### a) Transaction Flow
Setelah pelanggan membayar virtual account melalui channel bank (ATM, I-BANK, SMS, dll.), core banking akan mengirimkan notifikasi ke Maja Payment System. Jika status pembayaran berhasil, sistem akan memperbarui invoice, lalu mengirimkan notifikasi pembayaran ke merchant.

Alur: Customer membayar -> Source of Fund mengirim notifikasi ke Maja -> Maja update invoice -> Maja kirim notifikasi ke Merchant & Pelanggan.

### b) MAJA Notification Request (to merchant)
| Field | Type | Description |
| :--- | :--- | :--- |
| code | String | Kode Respon |
| message | String | Pesan Respon |
| type | String | Tipe transaksi (“payment”) |
| id | Long | ID Invoice |
| number | Long | Nomor Invoice |
| amount | Double | Jumlah Pembayaran |
| remainingAmount | Double | Sisa Jumlah |
| virtualAccount | String | Nomor virtual account (Bukan Virtual Account bank) |
| date | String | Tanggal Transaksi |
| bankCode | String | Kode Bank |
| bankName | String | Nama Bank |
| ref | String | Kode Referensi |
| channel | String | Channel Pembayaran |
| name | String | Nama Pelanggan |
| phone | String | Telepon Pelanggan |
| email | String | Email Pelanggan |
| address | String | Alamat Pelanggan |


### c) Example Notification
```json
{
  "code": "00",
  "message": "Transaksi berhasil",
  "type": "payment",
  "id": 109021,
  "number": 8087,
  "amount": 100000,
  "remainingAmount": 0,
  "virtualAccount": "88081234567",
  "date": "2021-11-22",
  "bankCode": "BSM",
  "bankName": "Bank Syariah Mandiri",
  "ref": "20211221304320001",
  "channel": "ATM",
  "name": "Alfiyah",
  "phone": "",
  "email": "alfiyah@sebuahdomain.com",
  "address": ""
}
```


## 7. Inquiry
### a) End Point
**HTTP Method:** POST  
**URL:** `https://billing-dev.maja.id/api/v2/inquiry`

### b) Post Body
| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| va | String | Yes | Nomor Virtual Account |
| invoiceNumber | String | Yes | Nomor Invoice |
| amount | Double | No | Jumlah |


### c) Example Request
```json
{
  "va": "80801234567",
  "invoiceNumber": "INV80808080",
  "amount": "100000"
}
```


### d) Example Response
```json
{
  "code": "01",
  "message": "Tagihan sudah terbayar",
  "data": {
    "date": "2020-11-21",
    "amount": 100000,
    "name": "Alfiyah",
    "email": "alfiyah@sebuahdomain.com",
    "va": "88081234567",
    "attribute1": "Fasilkom",
    "attribute2": "Manajemen Sistem Informasi",
    "items": [
      {
        "description": "Registration",
        "unitPrice": 100000,
        "qty": 1,
        "amount": 100000
      }
    ],
    "paid": true,
    "remainingAmount": 0
  }
}
```


## 8. Cancel the Invoice.
### a) End Point
**HTTP Method:** POST  
**URL:** `https://billing-dev.maja.id/api/v2/cancel`

### b) Post Data
| Field | Type | Required? | Description |
| :--- | :--- | :--- | :--- |
| va | String | No | Nomor Virtual Account |
| invoiceNumber | String | Yes | Nomor Invoice |
| amount | Double | No | Jumlah |


### c) Example Request
```json
{
  "va": "80801234567",
  "invoiceNumber": "INV12345",
  "amount": "100000"
}
```


### d) Example Response
```json
{
  "code": "00",
  "message": "Tagihan dibatalkan"
}
```


## Payment Method
| Code | Description |
| :--- | :--- |
| mandiri | Bank Mandiri |
| bni | BNI 46 |
| bri | BRI |
| hana | Keb Hana Bank |
| cimb | CIMB Niaga |
| bii | BII / Maybank |
| danamon | Bank Danamon |
| permata | Bank Permata |
| permatasyariah | Bank Permata Syariah |
| bca | BCA |
| bsi | BSM / BSI |

```