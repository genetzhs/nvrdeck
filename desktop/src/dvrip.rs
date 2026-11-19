//! XMeye / NetSurveillance «DVRIP» (θύρα 34567): σύνδεση, συμβάντα κίνησης, λίστα εγγραφών, λήψη εγγραφής.
//! Πακέτο: κεφαλίδα 20 bytes (0xFF, ver, 2 κενά, session u32, seq u32, 2 κενά, msg u16, len u32) + JSON + "\n\0".
#![allow(dead_code)]
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

pub const PORT: u16 = 34567;

pub struct Dvr { s: TcpStream, session: u32, seq: u32 }

#[derive(Debug, Clone)]
pub struct RecFile { pub channel: u32, pub begin: String, pub end: String, pub name: String, pub size_kb: u64, pub raw: Value }

#[derive(Debug, Clone)]
pub struct Alarm { pub channel: u32, pub event: String, pub start: bool, pub time: String }

/// Ο κατακερματισμός κωδικού του XMeye (MD5 → 8 χαρακτήρες).
pub fn sofia_hash(p: &str) -> String {
    let d = md5::compute(p.as_bytes());
    (0..8).map(|i| {
        let n = (d[2 * i] as u32 + d[2 * i + 1] as u32) % 62;
        (if n < 10 { b'0' + n as u8 } else if n < 36 { b'A' + (n - 10) as u8 } else { b'a' + (n - 36) as u8 }) as char
    }).collect()
}

impl Dvr {
    pub fn connect(host: &str, port: u16, user: &str, pass: &str) -> anyhow::Result<Dvr> {
        let addr = (host, port).to_socket_addrs()?.next().ok_or_else(|| anyhow::anyhow!("bad address"))?;
        let s = TcpStream::connect_timeout(&addr, Duration::from_secs(5))?;
        s.set_read_timeout(Some(Duration::from_secs(10)))?;
        let mut d = Dvr { s, session: 0, seq: 0 };
        let r = d.call(1000, json!({"EncryptType": "MD5", "LoginType": "DVRIP-Web", "PassWord": sofia_hash(pass), "UserName": user}))?;
        match r["Ret"].as_i64() {
            Some(100) | Some(515) => {}
            Some(203) | Some(205) => anyhow::bail!("wrong username or password"),
            other => anyhow::bail!("login refused ({other:?})"),
        }
        d.session = r["SessionID"].as_str().and_then(|x| u32::from_str_radix(x.trim_start_matches("0x"), 16).ok()).unwrap_or(0);
        Ok(d)
    }

    fn sid(&self) -> String { format!("0x{:08X}", self.session) }

    fn send(&mut self, msg: u16, body: &Value) -> anyhow::Result<()> {
        let mut data = serde_json::to_vec(body)?;
        data.extend_from_slice(b"\x0a\x00");
        let mut h = Vec::with_capacity(20 + data.len());
        h.extend_from_slice(&[0xFF, 0x00, 0, 0]);
        h.extend_from_slice(&self.session.to_le_bytes());
        h.extend_from_slice(&self.seq.to_le_bytes());
        h.extend_from_slice(&[0, 0]);
        h.extend_from_slice(&msg.to_le_bytes());
        h.extend_from_slice(&(data.len() as u32).to_le_bytes());
        h.extend_from_slice(&data);
        self.s.write_all(&h)?;
        self.seq += 1;
        Ok(())
    }

    /// Διαβάζει ένα πακέτο: (msg id, σώμα)
    pub fn recv(&mut self) -> anyhow::Result<(u16, Vec<u8>)> {
        let mut h = [0u8; 20];
        self.s.read_exact(&mut h)?;
        anyhow::ensure!(h[0] == 0xFF, "bad packet");
        let msg = u16::from_le_bytes([h[14], h[15]]);
        let len = u32::from_le_bytes([h[16], h[17], h[18], h[19]]) as usize;
        anyhow::ensure!(len < 16 << 20, "packet too big");
        let mut b = vec![0u8; len];
        self.s.read_exact(&mut b)?;
        Ok((msg, b))
    }

    fn json_of(b: &[u8]) -> Value {
        let end = b.iter().rposition(|&c| c == b'}').map(|i| i + 1).unwrap_or(b.len());
        serde_json::from_slice(&b[..end]).unwrap_or(Value::Null)
    }

    fn call(&mut self, msg: u16, body: Value) -> anyhow::Result<Value> {
        self.send(msg, &body)?;
        loop {
            let (m, b) = self.recv()?;
            if std::env::var_os("NVRDECK_DEBUG").is_some() { eprintln!("  sent {msg} got {m} ({} bytes) ret={:?}", b.len(), Self::json_of(&b)["Ret"]); }
            if m == msg + 1 { return Ok(Self::json_of(&b)); }
        }
    }

    pub fn keepalive(&mut self) -> anyhow::Result<()> {
        let sid = self.sid();
        self.call(1006, json!({"Name": "KeepAlive", "SessionID": sid})).map(|_| ())
    }

    /// Αριθμός καναλιών βίντεο του καταγραφικού.
    pub fn channels(&mut self) -> anyhow::Result<u32> {
        let sid = self.sid();
        let r = self.call(1020, json!({"Name": "SystemInfo", "SessionID": sid}))?;
        Ok(r["SystemInfo"]["VideoInChannel"].as_u64().unwrap_or(0) as u32)
    }

    /// Λίστα εγγραφών. `event`: "*" όλες, "M" κίνηση. Χρόνος "YYYY-MM-DD HH:MM:SS". Κανάλι 0-based.
    pub fn files(&mut self, channel: u32, begin: &str, end: &str, event: &str) -> anyhow::Result<Vec<RecFile>> {
        let sid = self.sid();
        let mut out = vec![];
        let mut from = begin.to_string();
        // το καταγραφικό επιστρέφει έως ~64 αρχεία ανά κλήση· συνεχίζουμε από το τελευταίο
        for _ in 0..50 {
            let r = self.call(1440, json!({"Name": "OPFileQuery", "SessionID": sid, "OPFileQuery": {
                "BeginTime": from, "EndTime": end, "Channel": channel, "DriverTypeMask": "0x0000FFFF",
                "Event": event, "Type": "h264", "StreamType": "0x00000000", "HighChannel": 0, "LowChannel": 1u32 << channel.min(31),
            }}))?;
            let list = r["OPFileQuery"].as_array().cloned().unwrap_or_default();
            let n = list.len();
            for f in list {
                let size = f["FileLength"].as_str().and_then(|x| u64::from_str_radix(x.trim_start_matches("0x"), 16).ok()).unwrap_or(0);
                out.push(RecFile { channel, begin: f["BeginTime"].as_str().unwrap_or("").into(), end: f["EndTime"].as_str().unwrap_or("").into(),
                                   name: f["FileName"].as_str().unwrap_or("").into(), size_kb: size, raw: f });
            }
            if n < 64 { break; }
            let last = out.last().map(|f| f.end.clone()).unwrap_or_default();
            if last.is_empty() || last == from { break; }
            from = last;
        }
        out.dedup_by(|a, b| a.name == b.name);
        Ok(out)
    }

    /// Εγγραφή στα συμβάντα (κίνηση κ.λπ.). Μετά καλούμε `next_alarm` σε βρόχο.
    pub fn subscribe_alarms(&mut self) -> anyhow::Result<()> {
        let sid = self.sid();
        self.call(1500, json!({"Name": "", "SessionID": sid})).map(|_| ())
    }

    pub fn next_alarm(&mut self) -> anyhow::Result<Option<Alarm>> {
        let (m, b) = match self.recv() {
            Ok(x) => x,
            Err(e) if e.downcast_ref::<std::io::Error>().map_or(false, |io| matches!(io.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut)) => return Ok(None),
            Err(e) => return Err(e),
        };
        if m != 1504 { return Ok(None); }
        let v = Self::json_of(&b);
        let a = &v["AlarmInfo"];
        Ok(Some(Alarm { channel: a["Channel"].as_u64().unwrap_or(0) as u32, event: a["Event"].as_str().unwrap_or("").into(),
                        start: a["Status"].as_str() == Some("Start"), time: a["StartTime"].as_str().unwrap_or("").into() }))
    }

    /// Κατεβάζει μια εγγραφή ως ακατέργαστο H.264/H.265 (για μετατροπή σε MP4 με ffmpeg).
    pub fn download(&mut self, f: &RecFile, out: &std::path::Path, mut progress: impl FnMut(u64)) -> anyhow::Result<u64> {
        let sid = self.sid();
        let pb = |action: &str| json!({"Name": "OPPlayBack", "SessionID": sid, "OPPlayBack": {
            "Action": action, "StartTime": f.begin, "EndTime": f.end,
            "Parameter": {"FileName": f.name, "PlayMode": "ByName", "TransMode": "TCP", "Value": 0, "StreamType": 0, "IntelligentPlayBackEvent": "", "IntelligentPlayBackSpeed": 0},
        }});
        self.call(1420, pb("Claim"))?;
        self.send(1424, &pb("DownloadStart"))?;
        let mut file = std::fs::File::create(out)?;
        let mut total = 0u64;
        self.s.set_read_timeout(Some(Duration::from_secs(15)))?;
        loop {
            let (m, b) = self.recv()?;
            match m {
                1426 => {
                    if b.is_empty() { break; }
                    total += b.len() as u64;
                    file.write_all(&b)?;
                    progress(total);
                }
                1425 => {} // απάντηση στο DownloadStart
                _ => {}
            }
        }
        let _ = self.send(1424, &pb("DownloadStop"));
        Ok(total)
    }
}

/// Αφαιρεί τις κεφαλίδες καρέ του XMeye (0x000001FC/FD/FA/F9) ώστε να μείνει καθαρό H.264/H.265 bitstream.
pub fn strip_frames(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0;
    while i + 8 <= raw.len() {
        if raw[i] == 0 && raw[i + 1] == 0 && raw[i + 2] == 1 && (0xF9..=0xFD).contains(&raw[i + 3]) {
            let (hdr, len_at) = match raw[i + 3] { 0xFC | 0xFE => (16, 12), 0xFD => (12, 8), 0xFA => (8, 4), 0xF9 => (8, 4), _ => (8, 4) };
            if i + hdr > raw.len() { break; }
            let len = u32::from_le_bytes([raw[i + len_at], raw[i + len_at + 1], raw[i + len_at + 2], raw[i + len_at + 3]]) as usize;
            let start = i + hdr;
            let end = (start + len).min(raw.len());
            if matches!(raw[i + 3], 0xFC | 0xFD) { out.extend_from_slice(&raw[start..end]); }
            i = end;
        } else {
            i += 1;
        }
    }
    out
}

#[cfg(test)]
mod t {
    #[test]
    fn hash() {
        // γνωστή τιμή: ο κενός κωδικός του XMeye
        assert_eq!(super::sofia_hash(""), "tlJwpbo6");
    }
}
