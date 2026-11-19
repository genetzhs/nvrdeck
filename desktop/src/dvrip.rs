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
        let si = &r["SystemInfo"];
        if std::env::var_os("NVRDECK_DEBUG").is_some() {
            let keys: Vec<String> = si.as_object().map(|o| o.iter().filter(|(k, _)| k.contains("Chan") || k.contains("Num")).map(|(k, v)| format!("{k}={v}")).collect()).unwrap_or_default();
            eprintln!("  SystemInfo channel fields: {}", keys.join(", "));
        }
        let n = |k: &str| si[k].as_u64().unwrap_or(0) as u32;
        Ok(n("VideoInChannel").max(n("DigChannel")).max(n("CombineSwitch").min(0)))
    }

    /// Πληροφορίες δίσκων: (πλήθος, συνολικά MB, ελεύθερα MB)
    pub fn storage(&mut self) -> anyhow::Result<(usize, u64, u64)> {
        let sid = self.sid();
        let r = self.call(1020, json!({"Name": "StorageInfo", "SessionID": sid}))?;
        let hx = |v: &Value| v.as_str().and_then(|x| u64::from_str_radix(x.trim_start_matches("0x"), 16).ok()).unwrap_or(0);
        let disks = r["StorageInfo"].as_array().cloned().unwrap_or_default();
        let (mut tot, mut free) = (0, 0);
        for d in &disks { for p in d["Partition"].as_array().cloned().unwrap_or_default() { tot += hx(&p["TotalSpace"]); free += hx(&p["RemainSpace"]); } }
        Ok((disks.len(), tot, free))
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
                "Event": event, "Type": "h264",
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

    /// Κατεβάζει μια εγγραφή και δίνει τα δεδομένα σταδιακά στο `sink` (επιστρέφει false για διακοπή).
    /// Ροή XMeye: δεύτερη σύνδεση με το ίδιο session → Claim (1424) εκεί, DownloadStart (1420) στην κύρια,
    /// τα δεδομένα έρχονται στη δεύτερη ως 1426 (κενό πακέτο = τέλος).
    pub fn stream(&mut self, host: &str, port: u16, f: &RecFile, mut sink: impl FnMut(&[u8]) -> bool) -> anyhow::Result<u64> {
        let sid = self.sid();
        let pb = |action: &str| json!({"Name": "OPPlayBack", "SessionID": sid, "OPPlayBack": {
            "Action": action, "StartTime": f.begin, "EndTime": f.end,
            "Parameter": {"FileName": f.name, "PlayMode": "ByName", "TransMode": "TCP", "Value": 0, "StreamType": 0, "IntelligentPlayBackEvent": "", "IntelligentPlayBackSpeed": 0},
        }});
        let addr = (host, port).to_socket_addrs()?.next().ok_or_else(|| anyhow::anyhow!("bad address"))?;
        let mut data = Dvr { s: TcpStream::connect_timeout(&addr, Duration::from_secs(5))?, session: self.session, seq: 0 };
        data.s.set_read_timeout(Some(Duration::from_secs(10)))?;
        data.call(1424, pb("Claim"))?;
        self.call(1420, pb("DownloadStart"))?;
        let mut total = 0u64;
        loop {
            let (m, b) = data.recv()?;
            if m != 1426 { continue; }
            if b.is_empty() { break; }
            total += b.len() as u64;
            if !sink(&b) { break; }
        }
        let _ = self.send(1420, &pb("DownloadStop"));
        Ok(total)
    }

    /// Αναπαραγωγή από συγκεκριμένη ώρα (PlayMode ByTime): το καταγραφικό ξεκινά από το `start`.
    pub fn stream_time(&mut self, host: &str, port: u16, channel: u32, start: &str, end: &str, fname: &str, variant: u8, mut sink: impl FnMut(&[u8]) -> bool) -> anyhow::Result<u64> {
        let sid = self.sid();
        let pb = |action: &str| {
            let mut par = json!({"FileName": "", "PlayMode": "ByTime", "TransMode": "TCP", "Value": channel, "StreamType": 0, "IntelligentPlayBackEvent": "", "IntelligentPlayBackSpeed": 0});
            if variant == 1 { par["Channel"] = json!(channel); par["Value"] = json!(0); }
            if variant == 3 { par["FileName"] = json!(fname); par["PlayMode"] = json!("ByName"); par["Value"] = json!(0); }
            json!({"Name": "OPPlayBack", "SessionID": sid, "OPPlayBack": { "Action": action, "StartTime": start, "EndTime": end, "Parameter": par }})
        };
        let addr = (host, port).to_socket_addrs()?.next().ok_or_else(|| anyhow::anyhow!("bad address"))?;
        let mut data = Dvr { s: TcpStream::connect_timeout(&addr, Duration::from_secs(5))?, session: self.session, seq: 0 };
        data.s.set_read_timeout(Some(Duration::from_secs(10)))?;
        let c = data.call(1424, pb("Claim"))?;
        anyhow::ensure!(c["Ret"].as_i64() == Some(100), "claim refused ({:?})", c["Ret"]);
        let r = self.call(1420, pb(if variant >= 2 { "DownloadStart" } else { "Start" }))?;
        anyhow::ensure!(r["Ret"].as_i64() == Some(100), "start refused ({:?})", r["Ret"]);
        let mut total = 0u64;
        loop {
            let (m, b) = data.recv()?;
            if m != 1426 { continue; }
            if b.is_empty() { break; }
            total += b.len() as u64;
            if !sink(&b) { break; }
        }
        let _ = self.send(1420, &pb("Stop"));
        Ok(total)
    }

    /// Κατεβάζει μια εγγραφή σε αρχείο (ακατέργαστο ρεύμα XMeye).
    pub fn download(&mut self, host: &str, port: u16, f: &RecFile, out: &std::path::Path, max: u64, mut progress: impl FnMut(u64)) -> anyhow::Result<u64> {
        let mut file = std::fs::File::create(out)?;
        let mut total = 0u64;
        let mut err = None;
        self.stream(host, port, f, |b| {
            if let Err(e) = file.write_all(b) { err = Some(e); return false; }
            total += b.len() as u64;
            progress(total);
            total < max
        })?;
        if let Some(e) = err { return Err(e.into()); }
        Ok(total)
    }
}

/// Σταδιακή αφαίρεση κεφαλίδων καρέ: δίνουμε κομμάτια, παίρνουμε ολόκληρα καρέ βίντεο (Annex-B).
#[derive(Default)]
pub struct FrameParser { buf: Vec<u8>, pub time: String, pub fps: u32, pub hevc: bool, pub width: u32, pub height: u32 }

impl FrameParser {
    /// Επιστρέφει (καρέ, είναι I-frame)
    pub fn push(&mut self, data: &[u8]) -> Vec<(Vec<u8>, bool)> {
        self.buf.extend_from_slice(data);
        let mut out = vec![];
        let mut i = 0;
        let b = &self.buf;
        while i + 16 <= b.len() {
            if !(b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 && (0xF9..=0xFD).contains(&b[i + 3])) { i += 1; continue; }
            let t = b[i + 3];
            let (hdr, len) = match t {
                0xFC => (16, u32::from_le_bytes([b[i + 12], b[i + 13], b[i + 14], b[i + 15]]) as usize),
                0xFD => (8, u32::from_le_bytes([b[i + 4], b[i + 5], b[i + 6], b[i + 7]]) as usize),
                _ => (8, u16::from_le_bytes([b[i + 6], b[i + 7]]) as usize),
            };
            if len > 8 << 20 { i += 1; continue; }
            if i + hdr + len > b.len() { break; } // περιμένουμε τα υπόλοιπα
            if t == 0xFC {
                let dt = u32::from_le_bytes([b[i + 8], b[i + 9], b[i + 10], b[i + 11]]);
                self.time = format!("{:04}-{:02}-{:02} {:02}:{:02}:{:02}", 2000 + (dt >> 26), (dt >> 22) & 15, (dt >> 17) & 31, (dt >> 12) & 31, (dt >> 6) & 63, dt & 63);
                self.fps = (b[i + 5] as u32).clamp(1, 60);
                self.hevc = b[i + 4] == 3 || b[i + 4] == 0x12;
                self.width = b[i + 6] as u32 * 8;
                self.height = b[i + 7] as u32 * 8;
            }
            if t == 0xFC || t == 0xFD { out.push((b[i + hdr..i + hdr + len].to_vec(), t == 0xFC)); }
            i += hdr + len;
        }
        self.buf.drain(..i);
        out
    }
}

/// (fps, H.265;) από την πρώτη κεφαλίδα I-frame (0x000001FC: byte 4 = τύπος, byte 5 = fps).
pub fn stream_info(raw: &[u8]) -> (u32, bool) {
    raw.windows(6).find(|w| w[..4] == [0, 0, 1, 0xFC]).map(|w| (w[5] as u32, w[4] == 3 || w[4] == 0x12)).unwrap_or((15, false))
}

/// Αφαιρεί τις κεφαλίδες καρέ του XMeye (0x000001FC/FD/FA/F9) ώστε να μείνει καθαρό H.264/H.265 bitstream.
pub fn strip_frames(raw: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(raw.len());
    let mut i = 0;
    while i + 16 <= raw.len() {
        if raw[i] == 0 && raw[i + 1] == 0 && raw[i + 2] == 1 && (0xF9..=0xFD).contains(&raw[i + 3]) {
            // FC: I-frame, 16 bytes κεφαλίδα, μήκος u32 στο 12 · FD: P-frame, 8 bytes, μήκος u32 στο 4
            // FA: ήχος, F9: πληροφορίες — 8 bytes, μήκος u16 στο 6 (παραλείπονται)
            let (hdr, len) = match raw[i + 3] {
                0xFC => (16, u32::from_le_bytes([raw[i + 12], raw[i + 13], raw[i + 14], raw[i + 15]]) as usize),
                0xFD => (8, u32::from_le_bytes([raw[i + 4], raw[i + 5], raw[i + 6], raw[i + 7]]) as usize),
                _ => (8, u16::from_le_bytes([raw[i + 6], raw[i + 7]]) as usize),
            };
            if i + hdr > raw.len() { break; }
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
