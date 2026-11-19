//! Κρυπτογραφημένη μεταφορά καμερών PC → κινητό με QR.
//! Μορφή: "NVRDECK1:" + base64(salt[16] | nonce[12] | AES-256-GCM(json))
//! Κλειδί: PBKDF2-HMAC-SHA256(passphrase, salt, 200 000 επαναλήψεις).
use aes_gcm::{aead::Aead, Aes256Gcm, KeyInit, Nonce};
use base64::Engine;

pub const PREFIX: &str = "NVRDECK1:";
pub const ROUNDS: u32 = 200_000;

pub fn encrypt(plain: &[u8], pass: &str) -> anyhow::Result<String> {
    let mut salt = [0u8; 16];
    let mut nonce = [0u8; 12];
    getrandom::getrandom(&mut salt).map_err(|e| anyhow::anyhow!("{e}"))?;
    getrandom::getrandom(&mut nonce).map_err(|e| anyhow::anyhow!("{e}"))?;
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(pass.as_bytes(), &salt, ROUNDS, &mut key);
    let ct = Aes256Gcm::new_from_slice(&key)?.encrypt(Nonce::from_slice(&nonce), plain).map_err(|_| anyhow::anyhow!("encrypt"))?;
    let mut all = salt.to_vec();
    all.extend_from_slice(&nonce);
    all.extend_from_slice(&ct);
    Ok(format!("{PREFIX}{}", base64::engine::general_purpose::STANDARD_NO_PAD.encode(all)))
}

#[allow(dead_code)]
pub fn decrypt(s: &str, pass: &str) -> anyhow::Result<Vec<u8>> {
    let b = base64::engine::general_purpose::STANDARD_NO_PAD.decode(s.strip_prefix(PREFIX).ok_or_else(|| anyhow::anyhow!("format"))?)?;
    anyhow::ensure!(b.len() > 28, "short");
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2_hmac::<sha2::Sha256>(pass.as_bytes(), &b[..16], ROUNDS, &mut key);
    Aes256Gcm::new_from_slice(&key)?.decrypt(Nonce::from_slice(&b[16..28]), &b[28..]).map_err(|_| anyhow::anyhow!("wrong passphrase"))
}

/// QR ως εικόνα RGBA (μαύρο σε λευκό, με περιθώριο 4 modules).
pub fn qr_image(data: &str) -> anyhow::Result<(usize, Vec<u8>)> {
    let code = qrcode::QrCode::with_error_correction_level(data.as_bytes(), qrcode::EcLevel::L)?;
    let n = code.width();
    let q = 4;
    let size = n + 2 * q;
    let colors = code.to_colors();
    let mut px = vec![255u8; size * size * 4];
    for y in 0..n { for x in 0..n {
        if colors[y * n + x] == qrcode::Color::Dark {
            let o = ((y + q) * size + x + q) * 4;
            px[o..o + 3].copy_from_slice(&[0, 0, 0]);
        }
    }}
    Ok((size, px))
}

#[cfg(test)]
mod t {
    #[test]
    fn roundtrip() {
        let e = super::encrypt(b"hello", "pw").unwrap();
        assert_eq!(super::decrypt(&e, "pw").unwrap(), b"hello");
        assert!(super::decrypt(&e, "no").is_err());
    }
}
