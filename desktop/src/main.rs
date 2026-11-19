//! NVRDeck – ελαφρύ καταγραφικό ζωντανής προβολής για κάμερες IP.
//! Η αποκωδικοποίηση γίνεται από το ffmpeg (μία διεργασία ανά κανάλι).

mod share;
use eframe::egui::{self, Color32, RichText, Rounding, Stroke};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::Read;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const MAX_CH: usize = 7;

// ───────────────────────── Θέμα ─────────────────────────
#[derive(Clone, Copy)]
struct Pal { bg: Color32, panel: Color32, surface: Color32, hover: Color32, border: Color32, text: Color32, muted: Color32, accent: Color32, dark: bool }
const THEMES: [(&str, Pal); 3] = [
    ("Cinematic", Pal { bg: Color32::from_rgb(8, 8, 9), panel: Color32::from_rgb(18, 18, 20), surface: Color32::from_rgb(26, 27, 29),
        hover: Color32::from_rgb(36, 37, 40), border: Color32::from_rgb(44, 45, 49), text: Color32::from_rgb(243, 244, 248),
        muted: Color32::from_rgb(139, 144, 166), accent: Color32::from_rgb(139, 124, 255), dark: true }),
    ("Light", Pal { bg: Color32::from_rgb(244, 245, 251), panel: Color32::from_rgb(255, 255, 255), surface: Color32::from_rgb(240, 241, 248),
        hover: Color32::from_rgb(231, 233, 243), border: Color32::from_rgb(225, 227, 236), text: Color32::from_rgb(18, 19, 28),
        muted: Color32::from_rgb(106, 112, 136), accent: Color32::from_rgb(91, 75, 245), dark: false }),
    ("Green", Pal { bg: Color32::from_rgb(237, 245, 240), panel: Color32::from_rgb(250, 253, 251), surface: Color32::from_rgb(226, 238, 230),
        hover: Color32::from_rgb(214, 231, 220), border: Color32::from_rgb(200, 220, 207), text: Color32::from_rgb(20, 40, 28),
        muted: Color32::from_rgb(88, 112, 96), accent: Color32::from_rgb(28, 140, 84), dark: false }),
];
static THEME: AtomicU32 = AtomicU32::new(0);
fn pal() -> Pal { THEMES[(THEME.load(Ordering::Relaxed) as usize).min(THEMES.len() - 1)].1 }
#[allow(non_snake_case)] fn BG() -> Color32 { pal().bg }
#[allow(non_snake_case)] fn PANEL() -> Color32 { pal().panel }
#[allow(non_snake_case)] fn SURFACE() -> Color32 { pal().surface }
#[allow(non_snake_case)] fn BORDER() -> Color32 { pal().border }
#[allow(non_snake_case)] fn TEXT() -> Color32 { pal().text }
#[allow(non_snake_case)] fn MUTED() -> Color32 { pal().muted }
#[allow(non_snake_case)] fn ACCENT() -> Color32 { pal().accent }
const OK: Color32 = Color32::from_rgb(62, 240, 166);
const BAD: Color32 = Color32::from_rgb(255, 92, 122);

fn load_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    fonts.font_data.insert("geist".into(), egui::FontData::from_static(include_bytes!("../assets/geist_regular.ttf")));
    fonts.font_data.insert("geist_semibold".into(), egui::FontData::from_static(include_bytes!("../assets/geist_semibold.ttf")));
    fonts.font_data.insert("geist_mono".into(), egui::FontData::from_static(include_bytes!("../assets/geist_mono.ttf")));
    fonts.families.entry(egui::FontFamily::Proportional).or_default().insert(0, "geist".into());
    fonts.families.entry(egui::FontFamily::Monospace).or_default().insert(0, "geist_mono".into());
    fonts.families.insert(egui::FontFamily::Name("semibold".into()), vec!["geist_semibold".into(), "geist".into()]);
    // εφεδρική γραμματοσειρά για σύμβολα/emoji που δεν έχει η Geist
    for path in ["/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/TTF/DejaVuSans.ttf"] {
        if let Ok(bytes) = std::fs::read(path) {
            fonts.font_data.insert("sys".into(), egui::FontData::from_owned(bytes));
            fonts.families.entry(egui::FontFamily::Proportional).or_default().push("sys".into());
            break;
        }
    }
    ctx.set_fonts(fonts);
}

fn semibold(size: f32) -> egui::FontId { egui::FontId::new(size, egui::FontFamily::Name("semibold".into())) }

/// Φόντο: λεία κάθετη διαβάθμιση μαύρο → ανθρακί γκρι (στα ανοιχτά θέματα: απαλό γκρι).
fn ambient_glow(ctx: &egui::Context) {
    let p = pal();
    let r = ctx.screen_rect();
    let (top, bottom) = if p.dark {
        (Color32::from_rgb(8, 8, 9), Color32::from_rgb(28, 29, 32))
    } else {
        (p.bg, p.surface)
    };
    let mut mesh = egui::Mesh::default();
    mesh.colored_vertex(r.left_top(), top);
    mesh.colored_vertex(r.right_top(), top);
    mesh.colored_vertex(r.left_bottom(), bottom);
    mesh.colored_vertex(r.right_bottom(), bottom);
    mesh.add_triangle(0, 1, 2);
    mesh.add_triangle(1, 3, 2);
    ctx.layer_painter(egui::LayerId::background()).add(egui::Shape::mesh(mesh));
}

fn apply_theme(ctx: &egui::Context, idx: u32) {
    THEME.store(idx, Ordering::Relaxed);
    let P = pal();
    let mut v = if P.dark { egui::Visuals::dark() } else { egui::Visuals::light() };
    v.panel_fill = PANEL();
    v.window_fill = PANEL();
    v.extreme_bg_color = BG();
    v.faint_bg_color = SURFACE();
    v.override_text_color = Some(TEXT());
    v.window_rounding = Rounding::same(22.0);
    v.window_stroke = Stroke::new(1.0, BORDER());
    v.selection.bg_fill = ACCENT().linear_multiply(if P.dark { 0.35 } else { 0.25 });
    v.selection.stroke = Stroke::new(1.0, ACCENT());
    v.hyperlink_color = ACCENT();
    for w in [&mut v.widgets.inactive, &mut v.widgets.hovered, &mut v.widgets.active, &mut v.widgets.open] {
        w.rounding = Rounding::same(12.0);
    }
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0, BORDER());
    v.widgets.inactive.bg_fill = SURFACE();
    v.widgets.inactive.weak_bg_fill = SURFACE();
    v.widgets.inactive.bg_stroke = Stroke::new(1.0, BORDER());
    v.widgets.hovered.bg_fill = P.hover;
    v.widgets.hovered.weak_bg_fill = P.hover;
    v.widgets.hovered.bg_stroke = Stroke::new(1.0, P.border);
    v.widgets.active.bg_fill = P.hover;
    v.widgets.active.weak_bg_fill = P.hover;
    ctx.set_visuals(v);

    ctx.style_mut(|s| {
        s.spacing.item_spacing = egui::vec2(8.0, 8.0);
        s.spacing.button_padding = egui::vec2(16.0, 8.0);
        s.spacing.interact_size.y = 34.0;
        // γρήγορη, όχι «νωχελική» ανταπόκριση
        s.animation_time = 0.12;
        use egui::{FontId, TextStyle::*};
        s.text_styles.insert(Body, FontId::proportional(14.0));
        s.text_styles.insert(Button, FontId::proportional(14.0));
        s.text_styles.insert(Heading, FontId::proportional(20.0));
        s.text_styles.insert(Small, FontId::proportional(12.0));
    });
}

/// «Γυάλινο» γέμισμα καρτών: διάφανο λευκό στο σκοτεινό θέμα, συμπαγές στα ανοιχτά.
fn glass() -> Color32 { if pal().dark { Color32::from_rgba_unmultiplied(255, 255, 255, 9) } else { PANEL() } }
fn glass_frame() -> egui::Frame {
    egui::Frame::none().fill(glass()).stroke(Stroke::new(1.0, BORDER())).rounding(22.0).inner_margin(egui::Margin::same(18.0))
}
/// Μικρή κεφαλαία ετικέτα + μεγάλος τίτλος.
fn screen_title(ui: &mut egui::Ui, eyebrow: &str, title: &str) {
    ui.label(RichText::new(eyebrow.to_uppercase()).color(MUTED()).size(11.5).extra_letter_spacing(1.6));
    ui.label(RichText::new(title).font(semibold(32.0)).color(TEXT()).extra_letter_spacing(-1.2));
}

fn primary(text: &str) -> egui::Button<'static> {
    egui::Button::new(RichText::new(text.to_string()).color(Color32::WHITE).font(semibold(14.0))).fill(ACCENT()).stroke(Stroke::NONE).rounding(999.0)
}
fn danger(text: &str) -> egui::Button<'static> {
    egui::Button::new(RichText::new(text.to_string()).color(BAD)).fill(SURFACE())
}

// ───────────────────────── Ρυθμίσεις / αποθήκευση ─────────────────────────
#[derive(Serialize, Deserialize, Clone, Copy, PartialEq, Debug)]
enum Proto { XMeye, Jovision, Hikvision, Dahua, Custom }

impl Proto {
    const ALL: [Proto; 5] = [Proto::XMeye, Proto::Jovision, Proto::Hikvision, Proto::Dahua, Proto::Custom];
    /// όνομα όπως στο enum του Android (Proto.XMEYE κ.λπ.)
    fn code(self) -> &'static str { match self { Proto::XMeye => "XMEYE", Proto::Jovision => "JOVISION", Proto::Hikvision => "HIKVISION", Proto::Dahua => "DAHUA", Proto::Custom => "CUSTOM" } }
    fn label(self) -> &'static str {
        match self {
            Proto::XMeye => "XMeye / NetSurveillance",
            Proto::Jovision => "Jovision",
            Proto::Hikvision => "Hikvision",
            Proto::Dahua => "Dahua",
            Proto::Custom => "Generic RTSP (custom path)",
        }
    }
    fn default_port(self) -> u16 {
        match self { Proto::Jovision => 5544, _ => 554 }
    }
}

#[derive(Serialize, Deserialize, Clone)]
struct Camera {
    name: String,
    host: String,
    port: u16,
    proto: Proto,
    user: String,
    pass: String,
    #[serde(default)]
    main_stream: bool,
    #[serde(default)]
    path: String,
    #[serde(default = "yes")]
    enabled: bool,
    #[serde(default = "one")]
    channel: u32,
    #[serde(default)]
    group: String,
    #[serde(default)]
    motion: bool,
}
fn yes() -> bool { true }
fn one() -> u32 { 1 }

impl Default for Camera {
    fn default() -> Self {
        Camera { name: String::new(), host: String::new(), port: 554, proto: Proto::XMeye, user: "admin".into(),
                 pass: String::new(), main_stream: false, path: String::new(), enabled: true, channel: 1, group: String::new(), motion: false }
    }
}

/// Κωδικοποιεί μόνο ό,τι σπάει το URL (@ : / ? # % κενό). Τα υπόλοιπα σύμβολα μένουν όπως είναι,
/// γιατί πολλές κάμερες/ffmpeg δεν αποκωδικοποιούν %XX στα στοιχεία σύνδεσης.
fn enc(s: &str) -> String {
    s.chars().map(|c| match c {
        '@' | ':' | '/' | '?' | '#' | '%' | ' ' => c.to_string().bytes().map(|b| format!("%{b:02X}")).collect(),
        _ => c.to_string(),
    }).collect()
}

impl Camera {
    fn url(&self) -> String {
        let (u, p) = (enc(&self.user), enc(&self.pass));
        let auth = if self.user.is_empty() { String::new() } else { format!("{u}:{p}@") };
        let base = format!("rtsp://{auth}{}:{}", self.host, self.port);
        let m = self.main_stream;
        let ch = self.channel.max(1);
        match self.proto {
            Proto::XMeye => format!("{base}/user={u}&password={p}&channel={ch}&stream={}.sdp?real_stream", if m { 0 } else { 1 }),
            Proto::Jovision => format!("{base}/live{}.264", if m { 0 } else { 1 }),
            Proto::Hikvision => format!("{base}/Streaming/Channels/{}", ch * 100 + if m { 1 } else { 2 }),
            Proto::Dahua => format!("{base}/cam/realmonitor?channel={ch}&subtype={}", if m { 0 } else { 1 }),
            Proto::Custom => format!("{base}/{}", self.path.trim_start_matches('/')),
        }
    }
    /// URL χωρίς κωδικό, για εμφάνιση στην οθόνη
    fn safe_url(&self) -> String {
        let mut c = self.clone();
        c.pass = if self.pass.is_empty() { String::new() } else { "••••".into() };
        c.url()
    }
}

#[derive(Serialize, Deserialize, Clone)]
struct Settings { width: usize, height: usize, fps: u32, #[serde(default)] theme: u32, #[serde(default = "yes")] show_clock: bool, #[serde(default)] layout: u32, #[serde(default)] main_ch: usize,
    #[serde(default = "yes")] auto_hd: bool, #[serde(default = "yes")] alerts: bool, #[serde(default = "alert_secs")] alert_after: u64,
    #[serde(default)] group: String, #[serde(default = "sens")] motion_sens: u32, #[serde(default)] wall_autologin: bool }
fn sens() -> u32 { 50 }
fn alert_secs() -> u64 { 60 }
impl Default for Settings { fn default() -> Self { Settings { width: 960, height: 540, fps: 12, theme: 0, show_clock: true, layout: 0, main_ch: 0, auto_hd: true, alerts: true, alert_after: 60, group: String::new(), motion_sens: 50, wall_autologin: false } } }

#[derive(Serialize, Deserialize, Default)]
struct Store {
    #[serde(default)]
    admin_user: String,
    #[serde(default)]
    admin_salt: String,
    #[serde(default)]
    admin_hash: String,
    #[serde(default)]
    settings: Settings,
    #[serde(default)]
    camera: Vec<Camera>,
}

fn store_path() -> PathBuf { dirs::config_dir().unwrap_or_else(|| PathBuf::from(".")).join("nvrdeck/nvrdeck.toml") }

impl Store {
    fn load() -> Store {
        std::fs::read_to_string(store_path()).ok().and_then(|t| toml::from_str(&t).ok()).unwrap_or_default()
    }
    fn save(&self) -> anyhow::Result<()> {
        let p = store_path();
        std::fs::create_dir_all(p.parent().unwrap())?;
        let tmp = p.with_extension("tmp");
        std::fs::write(&tmp, toml::to_string_pretty(self)?)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&tmp, std::fs::Permissions::from_mode(0o600))?;
        }
        std::fs::rename(tmp, p)?;
        Ok(())
    }
    fn hash(salt: &str, pass: &str) -> String {
        let mut h = Sha256::new();
        h.update(salt.as_bytes());
        h.update(pass.as_bytes());
        let mut out = h.finalize();
        for _ in 0..100_000 {
            let mut h = Sha256::new();
            h.update(out);
            h.update(salt.as_bytes());
            out = h.finalize();
        }
        out.iter().map(|b| format!("{b:02x}")).collect()
    }
    fn set_admin(&mut self, user: &str, pass: &str) {
        let mut salt = [0u8; 16];
        let _ = getrandom::getrandom(&mut salt);
        self.admin_salt = salt.iter().map(|b| format!("{b:02x}")).collect();
        self.admin_user = user.to_string();
        self.admin_hash = Self::hash(&self.admin_salt, pass);
    }
    fn check(&self, user: &str, pass: &str) -> bool {
        user == self.admin_user && Self::hash(&self.admin_salt, pass) == self.admin_hash
    }
}

// ───────────────────────── Ροή βίντεο ─────────────────────────
#[derive(Default)]
struct Shared {
    frame: Option<Vec<u8>>,
    fresh: bool,
    status: String,
    last: Option<Instant>,
    last_wall: Option<chrono::DateTime<chrono::Local>>,
    fps: f32,
    src_res: String,
    reconnects: u32,
}

struct Stream {
    shared: Arc<Mutex<Shared>>,
    stop: Arc<AtomicBool>,
    pid: Arc<AtomicU32>,
    tex: Option<egui::TextureHandle>,
    w: usize,
    h: usize,
    prev: Vec<u8>,
    motion_at: Option<Instant>,
    motion_note: Option<Instant>,
}

impl Stream {
    fn start(url: String, s: Settings, ctx: egui::Context) -> Stream {
        let (w, h) = (s.width, s.height);
        let shared = Arc::new(Mutex::new(Shared { status: "Connecting…".into(), ..Default::default() }));
        let stop = Arc::new(AtomicBool::new(false));
        let pid = Arc::new(AtomicU32::new(0));
        let (sh, st, pd) = (shared.clone(), stop.clone(), pid.clone());
        std::thread::spawn(move || worker(url, s, sh, st, pd, ctx));
        Stream { shared, stop, pid, tex: None, w, h, prev: vec![], motion_at: None, motion_note: None }
    }
}

impl Drop for Stream {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let pid = self.pid.load(Ordering::SeqCst);
        if pid != 0 {
            let _ = Command::new("kill").arg(pid.to_string()).stderr(Stdio::null()).status();
        }
    }
}


/// Βρίσκει το ffmpeg/ffprobe: δίπλα στο εκτελέσιμο (bundled), $NVRDECK_FFMPEG_DIR, PATH,
/// και στις συνηθισμένες θέσεις (οι εφαρμογές GUI στο macOS δεν βλέπουν το PATH του Homebrew).
fn tool(name: &str) -> PathBuf {
    let exe_dir = std::env::current_exe().ok().and_then(|p| p.parent().map(|d| d.to_path_buf()));
    let mut dirs: Vec<PathBuf> = vec![];
    if let Some(d) = exe_dir { dirs.push(d.clone()); dirs.push(d.join("../Resources")); }
    if let Ok(d) = std::env::var("NVRDECK_FFMPEG_DIR") { dirs.push(PathBuf::from(d)); }
    if let Some(path) = std::env::var_os("PATH") { dirs.extend(std::env::split_paths(&path)); }
    for d in ["/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/snap/bin"] { dirs.push(PathBuf::from(d)); }
    for d in dirs {
        let p = d.join(name);
        if p.is_file() { return p; }
    }
    PathBuf::from(name)
}

fn ffmpeg_cmd(url: &str, s: &Settings) -> Command {
    let (w, h, fps) = (s.width, s.height, s.fps);
    let mut c = Command::new(tool("ffmpeg"));
    c.args(["-hide_banner", "-loglevel", "info", "-nostats", "-nostdin", "-rtsp_transport", "tcp", "-timeout", "5000000",
            "-fflags", "nobuffer", "-flags", "low_delay", "-i", url, "-an", "-vf",
            &format!("fps={fps},scale={w}:{h}:force_original_aspect_ratio=increase,crop={w}:{h}"),
            "-pix_fmt", "rgba", "-f", "rawvideo", "-"])
        .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
    c
}

fn worker(url: String, s: Settings, sh: Arc<Mutex<Shared>>, stop: Arc<AtomicBool>, pid: Arc<AtomicU32>, ctx: egui::Context) {
    let size = s.width * s.height * 4;
    let mut attempt = 0u32;
    while !stop.load(Ordering::SeqCst) {
        {
            let mut g = sh.lock().unwrap();
            g.status = "Connecting…".into();
            if attempt > 0 { g.reconnects += 1; }
        }
        attempt += 1;
        ctx.request_repaint();
        match ffmpeg_cmd(&url, &s).spawn() {
            Ok(mut child) => {
                pid.store(child.id(), Ordering::SeqCst);
                let mut out = child.stdout.take().unwrap();
                let err = child.stderr.take().unwrap();
                let errbuf = Arc::new(Mutex::new(String::new()));
                let (eb, sh2) = (errbuf.clone(), sh.clone());
                std::thread::spawn(move || {
                    use std::io::BufRead;
                    for line in std::io::BufReader::new(err).lines().map_while(Result::ok) {
                        // π.χ. "Stream #0:0: Video: h264 (High), yuvj420p, 1920x1080, 25 fps"
                        if line.contains("Video:") && line.contains("Stream #0") {
                            if let Some(res) = line.split(&[',', ' '][..]).find(|t| {
                                let mut it = t.split('x');
                                matches!((it.next(), it.next()), (Some(a), Some(b)) if a.parse::<u32>().is_ok() && b.parse::<u32>().is_ok())
                            }) {
                                let codec = line.split("Video:").nth(1).and_then(|v| v.split_whitespace().next()).unwrap_or("").trim_end_matches(',');
                                sh2.lock().unwrap().src_res = format!("{res} {codec}");
                            }
                        }
                        let mut e = eb.lock().unwrap();
                        if e.len() < 4000 { e.push_str(&line); e.push('\n'); }
                    }
                });
                let mut buf = vec![0u8; size];
                let (mut win_start, mut win_frames) = (Instant::now(), 0u32);
                while !stop.load(Ordering::SeqCst) && out.read_exact(&mut buf).is_ok() {
                    win_frames += 1;
                    let mut g = sh.lock().unwrap();
                    if win_start.elapsed() >= Duration::from_secs(2) {
                        g.fps = win_frames as f32 / win_start.elapsed().as_secs_f32();
                        win_start = Instant::now();
                        win_frames = 0;
                    }
                    g.frame = Some(std::mem::replace(&mut buf, vec![0u8; size]));
                    g.fresh = true;
                    g.status.clear();
                    g.last = Some(Instant::now());
                    g.last_wall = Some(chrono::Local::now());
                    drop(g);
                    ctx.request_repaint();
                }
                let _ = child.kill();
                let _ = child.wait();
                pid.store(0, Ordering::SeqCst);
                std::thread::sleep(Duration::from_millis(100));
                let e = errbuf.lock().unwrap().to_lowercase();
                let mut g = sh.lock().unwrap();
                g.status = friendly_error(&e).into();
                g.fps = 0.0;
            }
            Err(_) => sh.lock().unwrap().status = "ffmpeg not found".into(),
        }
        ctx.request_repaint();
        for _ in 0..30 {
            if stop.load(Ordering::SeqCst) { return; }
            std::thread::sleep(Duration::from_millis(100));
        }
    }
}

// ───────────────────────── Στιγμιότυπα, εγγραφές, ειδοποιήσεις ─────────────────────────
fn media_dir(video: bool) -> PathBuf {
    let base = if video { dirs::video_dir() } else { dirs::picture_dir() };
    let d = base.or_else(dirs::home_dir).unwrap_or_else(|| PathBuf::from(".")).join("NVRDeck");
    let _ = std::fs::create_dir_all(&d);
    d
}

fn file_stem(cam: &Camera) -> String {
    let safe: String = cam.name.chars().map(|c| if c.is_alphanumeric() || c == '-' { c } else { '_' }).collect();
    format!("{}_{}", safe.trim_matches('_'), chrono::Local::now().format("%Y-%m-%d_%H-%M-%S"))
}

/// Στιγμιότυπο σε πλήρη ανάλυση από το κύριο stream (στο παρασκήνιο).
fn snapshot(cam: &Camera, inbox: Arc<Mutex<Vec<String>>>, ctx: egui::Context) {
    let mut c = cam.clone();
    c.main_stream = true;
    let url = c.url();
    let path = media_dir(false).join(format!("{}.jpg", file_stem(cam)));
    std::thread::spawn(move || {
        let ok = Command::new(tool("ffmpeg"))
            .args(["-hide_banner", "-loglevel", "error", "-nostdin", "-rtsp_transport", "tcp", "-timeout", "8000000", "-i", &url,
                   "-frames:v", "1", "-q:v", "2", "-y"]).arg(&path)
            .stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null())
            .status().map(|s| s.success()).unwrap_or(false);
        inbox.lock().unwrap().push(if ok { format!("Snapshot saved: {}", path.display()) } else { "Snapshot failed".into() });
        ctx.request_repaint();
    });
}

/// Εγγραφή MP4 χωρίς επανακωδικοποίηση (stream copy). Fragmented MP4: παίζει ακόμα κι αν διακοπεί απότομα.
struct Recording { child: std::process::Child, path: PathBuf, started: Instant }

fn start_recording(cam: &Camera) -> Option<Recording> {
    let mut c = cam.clone();
    c.main_stream = true;
    let path = media_dir(true).join(format!("{}.mp4", file_stem(cam)));
    let child = Command::new(tool("ffmpeg"))
        .args(["-hide_banner", "-loglevel", "error", "-rtsp_transport", "tcp", "-timeout", "8000000", "-i", &c.url(),
               "-map", "0:v:0", "-map", "0:a?", "-c:v", "copy", "-c:a", "aac", "-movflags", "+frag_keyframe+empty_moov+default_base_moof", "-y"])
        .arg(&path)
        .stdin(Stdio::piped()).stdout(Stdio::null()).stderr(Stdio::null())
        .spawn().ok()?;
    Some(Recording { child, path, started: Instant::now() })
}

fn stop_recording(mut r: Recording) -> PathBuf {
    use std::io::Write;
    if let Some(stdin) = r.child.stdin.as_mut() { let _ = stdin.write_all(b"q"); }
    let deadline = Instant::now() + Duration::from_secs(4);
    while Instant::now() < deadline {
        if let Ok(Some(_)) = r.child.try_wait() { return r.path; }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = r.child.kill();
    let _ = r.child.wait();
    r.path
}

/// Ειδοποίηση επιφάνειας εργασίας (Linux: notify-send, macOS: osascript).
fn desktop_notify(title: &str, body: &str) {
    #[cfg(target_os = "macos")]
    {
        let esc = |s: &str| s.replace('\\', "").replace('"', "'");
        let _ = Command::new("osascript").arg("-e")
            .arg(format!("display notification \"{}\" with title \"{}\"", esc(body), esc(title))).spawn();
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = Command::new("notify-send").args(["-a", "NVRDeck", "-i", "camera-web", title, body])
            .stdout(Stdio::null()).stderr(Stdio::null()).spawn();
    }
}

#[derive(Default, Clone)]
struct Health { online: f64, total: f64, down_since: Option<Instant>, alerted: bool }

fn friendly_error(e: &str) -> &'static str {
    if e.contains("401") || e.contains("unauthorized") { "Wrong username or password" }
    else if e.contains("404") || e.contains("not found") { "Wrong stream path" }
    else if e.contains("refused") { "Port is closed" }
    else if e.contains("timed out") || e.contains("timeout") || e.contains("no route") { "Camera not responding" }
    else { "No signal – reconnecting" }
}

/// Γρήγορη δοκιμή σύνδεσης με ffprobe (στο παρασκήνιο)
fn probe(url: String, out: Arc<Mutex<Option<Result<String, String>>>>, ctx: egui::Context) {
    std::thread::spawn(move || {
        let r = Command::new(tool("ffprobe"))
            .args(["-v", "error", "-rtsp_transport", "tcp", "-timeout", "5000000", "-select_streams", "v:0",
                   "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", &url])
            .stdin(Stdio::null()).output();
        let res = match r {
            Ok(o) if o.status.success() && !o.stdout.is_empty() => {
                let t = String::from_utf8_lossy(&o.stdout).trim().replace(',', " · ");
                Ok(format!("OK – {t}"))
            }
            Ok(o) => Err(friendly_error(&String::from_utf8_lossy(&o.stderr).to_lowercase()).to_string()),
            Err(_) => Err("ffprobe not found".into()),
        };
        *out.lock().unwrap() = Some(res);
        ctx.request_repaint();
    });
}


// ───────────────────────── Σάρωση δικτύου ─────────────────────────
#[derive(Clone)]
struct Found { host: String, rtsp_port: u16, proto: Proto, vendor: String, mac: String, onvif: bool }

#[derive(Default)]
struct ScanState { running: bool, done: usize, total: usize, found: Vec<Found>, subnets: Vec<String> }

fn local_subnets() -> Vec<String> {
    let out = Command::new("ip").args(["-4", "-o", "addr", "show", "up"]).output();
    let mut v = vec![];
    if let Ok(o) = out {
        for line in String::from_utf8_lossy(&o.stdout).lines() {
            let f: Vec<&str> = line.split_whitespace().collect();
            if f.len() < 4 { continue; }
            let ifc = f[1];
            if ifc == "lo" || ifc.starts_with("docker") || ifc.starts_with("br-") || ifc.starts_with("veth")
               || ifc.starts_with("virbr") || ifc.starts_with("tun") || ifc.starts_with("wg") { continue; }
            if let Some(ip) = f[3].split('/').next() {
                let o: Vec<&str> = ip.split('.').collect();
                if o.len() == 4 && o[0] != "169" {
                    let net = format!("{}.{}.{}", o[0], o[1], o[2]);
                    if !v.contains(&net) { v.push(net); }
                }
            }
        }
    }
    v
}

fn port_open(host: &str, port: u16, ms: u64) -> Option<std::net::TcpStream> {
    let addr: std::net::SocketAddr = format!("{host}:{port}").parse().ok()?;
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(ms)).ok()
}

fn is_rtsp(mut s: std::net::TcpStream, host: &str, port: u16) -> bool {
    use std::io::Write;
    let _ = s.set_read_timeout(Some(Duration::from_millis(1500)));
    let _ = s.set_write_timeout(Some(Duration::from_millis(1000)));
    if s.write_all(format!("OPTIONS rtsp://{host}:{port}/ RTSP/1.0\r\nCSeq: 1\r\n\r\n").as_bytes()).is_err() { return false; }
    let mut buf = [0u8; 64];
    match s.read(&mut buf) { Ok(n) if n > 0 => String::from_utf8_lossy(&buf[..n]).starts_with("RTSP/"), _ => false }
}

fn arp_mac(host: &str) -> String {
    std::fs::read_to_string("/proc/net/arp").ok().and_then(|t| t.lines()
        .find(|l| l.split_whitespace().next() == Some(host))
        .and_then(|l| l.split_whitespace().nth(3).map(|m| m.to_lowercase())))
        .filter(|m| m != "00:00:00:00:00:00").unwrap_or_default()
}

fn onvif_discover() -> Vec<String> {
    let msg = format!(r#"<?xml version="1.0"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:{:x}</w:MessageID><w:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"#,
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    let mut hosts = vec![];
    let Ok(sock) = std::net::UdpSocket::bind("0.0.0.0:0") else { return hosts };
    let _ = sock.set_read_timeout(Some(Duration::from_millis(500)));
    for _ in 0..2 { let _ = sock.send_to(msg.as_bytes(), "239.255.255.250:3702"); }
    let end = Instant::now() + Duration::from_secs(3);
    let mut buf = vec![0u8; 65535];
    while Instant::now() < end {
        if let Ok((_, from)) = sock.recv_from(&mut buf) {
            let h = from.ip().to_string();
            if !hosts.contains(&h) { hosts.push(h); }
        }
    }
    hosts
}

fn guess(host: &str, rtsp: u16, mac: &str) -> (Proto, String) {
    let quick = |p| port_open(host, p, 400).is_some();
    if mac.starts_with("e0:62:90") || rtsp == 5544 { return (Proto::Jovision, "Jovision".into()); }
    if quick(34567) { return (Proto::XMeye, "XMeye / NetSurveillance".into()); }
    if quick(37777) { return (Proto::Dahua, "Dahua".into()); }
    if quick(8000) { return (Proto::Hikvision, "Hikvision".into()); }
    (Proto::Custom, "Unknown type".into())
}

fn start_scan(state: Arc<Mutex<ScanState>>, ctx: egui::Context) {
    const RTSP_PORTS: [u16; 4] = [554, 5544, 8554, 10554];
    let subnets = local_subnets();
    let hosts: Vec<String> = subnets.iter().flat_map(|n| (1..=254).map(move |i| format!("{n}.{i}"))).collect();
    {
        let mut g = state.lock().unwrap();
        *g = ScanState { running: true, total: hosts.len(), subnets: subnets.clone(), ..Default::default() };
    }
    std::thread::spawn(move || {
        let onvif = std::thread::spawn(onvif_discover);
        let queue = Arc::new(Mutex::new(hosts));
        let mut workers = vec![];
        for _ in 0..96 {
            let (q, st, cx) = (queue.clone(), state.clone(), ctx.clone());
            workers.push(std::thread::spawn(move || loop {
                let Some(h) = q.lock().unwrap().pop() else { break };
                for p in RTSP_PORTS {
                    if let Some(s) = port_open(&h, p, 350) {
                        if is_rtsp(s, &h, p) {
                            let mac = arp_mac(&h);
                            let (proto, vendor) = guess(&h, p, &mac);
                            st.lock().unwrap().found.push(Found { host: h.clone(), rtsp_port: p, proto, vendor, mac, onvif: false });
                            cx.request_repaint();
                            break;
                        }
                    }
                }
                st.lock().unwrap().done += 1;
                cx.request_repaint();
            }));
        }
        for w in workers { let _ = w.join(); }
        let onv = onvif.join().unwrap_or_default();
        let mut g = state.lock().unwrap();
        for f in g.found.iter_mut() { if onv.contains(&f.host) { f.onvif = true; } }
        g.found.sort_by_key(|f| f.host.split('.').map(|o| o.parse::<u32>().unwrap_or(0)).fold(0u64, |a, b| a * 256 + b as u64));
        g.running = false;
        drop(g);
        ctx.request_repaint();
    });
}


// ───────────────────────── Διατάξεις πλέγματος ─────────────────────────
const LAYOUTS: [&str; 8] = ["Auto", "1", "2×2", "2×3", "1 + 4", "1 + 5", "3×3", "1 + 7"];

/// Θέσεις κελιών σε κανονικοποιημένες συντεταγμένες (0..1). Το πρώτο κελί είναι το «κύριο».
fn layout_cells(kind: u32, n: usize) -> Vec<egui::Rect> {
    let grid = |cols: usize, rows: usize| -> Vec<egui::Rect> {
        (0..cols * rows).map(|k| egui::Rect::from_min_size(
            egui::pos2((k % cols) as f32 / cols as f32, (k / cols) as f32 / rows as f32),
            egui::vec2(1.0 / cols as f32, 1.0 / rows as f32))).collect()
    };
    // μεγάλο κελί big×big μέσα σε πλέγμα g×g, τα υπόλοιπα γύρω του
    let big = |g: usize, b: usize| -> Vec<egui::Rect> {
        let u = 1.0 / g as f32;
        let mut v = vec![egui::Rect::from_min_size(egui::pos2(0.0, 0.0), egui::vec2(u * b as f32, u * b as f32))];
        for r in 0..g { for c in 0..g {
            if r < b && c < b { continue; }
            v.push(egui::Rect::from_min_size(egui::pos2(c as f32 * u, r as f32 * u), egui::vec2(u, u)));
        }}
        v
    };
    match kind {
        1 => grid(1, 1),
        2 => grid(2, 2),
        3 => grid(3, 2), // 2 σειρές × 3 στήλες
        4 => { // μεγάλη αριστερά, 4 μικρές σε στήλη δεξιά
            let mut v = vec![egui::Rect::from_min_size(egui::pos2(0.0, 0.0), egui::vec2(0.75, 1.0))];
            for i in 0..4 { v.push(egui::Rect::from_min_size(egui::pos2(0.75, i as f32 * 0.25), egui::vec2(0.25, 0.25))); }
            v
        }
        5 => big(3, 2),
        6 => grid(3, 3),
        7 => big(4, 3),
        // Auto: γεμίζει όλο το παράθυρο – η τελευταία σειρά απλώνεται, χωρίς κενά κελιά
        _ => {
            let n = n.max(1);
            let cols = (n as f32).sqrt().ceil() as usize;
            let rows = (n + cols - 1) / cols;
            let mut v = Vec::with_capacity(n);
            for r in 0..rows {
                let in_row = if r == rows - 1 { n - cols * (rows - 1) } else { cols };
                for c in 0..in_row {
                    v.push(egui::Rect::from_min_size(
                        egui::pos2(c as f32 / in_row as f32, r as f32 / rows as f32),
                        egui::vec2(1.0 / in_row as f32, 1.0 / rows as f32)));
                }
            }
            v
        }
    }
}

// ───────────────────────── Εφαρμογή ─────────────────────────
#[derive(PartialEq, Clone, Copy)]
enum Page { Live, Cameras, Playback, Settings }

struct Editor {
    idx: Option<usize>,
    cam: Camera,
    show_pass: bool,
    test: Arc<Mutex<Option<Result<String, String>>>>,
    testing: bool,
    err: String,
}

struct NvrForm {
    name: String,
    cam: Camera,
    first: u32,
    count: u32,
    show_pass: bool,
    test: Arc<Mutex<Option<Result<String, String>>>>,
    testing: bool,
    err: String,
}

struct App {
    store: Store,
    logged_in: bool,
    login_user: String,
    login_pass: String,
    login_pass2: String,
    login_err: String,
    page: Page,
    streams: Vec<Option<Stream>>,
    solo: Option<usize>,
    editor: Option<Editor>,
    confirm_delete: Option<usize>,
    settings_draft: Settings,
    toast: Option<(String, Instant)>,
    scan: Arc<Mutex<ScanState>>,
    scan_open: bool,
    nvr: Option<NvrForm>,
    hd: Option<(usize, Stream)>,
    zoom: f32,
    zoom_c: egui::Pos2,
    recs: std::collections::HashMap<usize, Recording>,
    inbox: Arc<Mutex<Vec<String>>>,
    health: Vec<Health>,
    last_tick: Instant,
    wall: bool,
    chrome: bool,
    qr: Option<QrState>,
    media: Vec<(PathBuf, bool, std::time::SystemTime, u64)>,
}

struct QrState { pass: String, pass2: String, tex: Option<egui::TextureHandle>, err: String, n: usize }

impl App {
    fn new(cc: &eframe::CreationContext) -> App {
        load_fonts(&cc.egui_ctx);
        let store = Store::load();
        apply_theme(&cc.egui_ctx, store.settings.theme);
        let settings_draft = store.settings.clone();
        App { store, logged_in: false, login_user: "admin".into(), login_pass: String::new(), login_pass2: String::new(),
              login_err: String::new(), page: Page::Live, streams: vec![], solo: None, editor: None,
              confirm_delete: None, settings_draft, toast: None,
              scan: Arc::new(Mutex::new(ScanState::default())), scan_open: false, nvr: None,
              hd: None, zoom: 1.0, zoom_c: egui::pos2(0.5, 0.5), recs: Default::default(),
              inbox: Arc::new(Mutex::new(vec![])), health: vec![], last_tick: Instant::now(),
              wall: false, chrome: true, qr: None, media: vec![] }
    }

    fn restart_streams(&mut self, ctx: &egui::Context) {
        self.streams.clear(); // Drop σταματά τα παλιά ffmpeg
        self.streams = self.store.camera.iter().map(|c| {
            c.enabled.then(|| Stream::start(c.url(), self.store.settings.clone(), ctx.clone()))
        }).collect();
        if self.solo.map_or(false, |i| i >= self.streams.len()) { self.solo = None; }
        self.hd = None;
        self.health = vec![Health::default(); self.store.camera.len()];
    }

    /// Υγεία καναλιών + ειδοποιήσεις όταν κάμερα μείνει εκτός για > N δευτερόλεπτα.
    fn tick_health(&mut self) {
        let dt = self.last_tick.elapsed().as_secs_f64();
        self.last_tick = Instant::now();
        if self.health.len() != self.streams.len() { self.health = vec![Health::default(); self.streams.len()]; }
        let after = Duration::from_secs(self.store.settings.alert_after.max(10));
        let mut notes = vec![];
        for (i, st) in self.streams.iter().enumerate() {
            let Some(st) = st else { continue };
            let live = st.shared.lock().unwrap().last.map_or(false, |t| t.elapsed() < Duration::from_secs(5));
            let h = &mut self.health[i];
            h.total += dt;
            let name = self.store.camera.get(i).map(|c| c.name.clone()).unwrap_or_default();
            if live {
                h.online += dt;
                if h.alerted { notes.push((format!("{name} is back online"), "The camera is streaming again.".to_string())); }
                h.down_since = None;
                h.alerted = false;
            } else {
                let since = *h.down_since.get_or_insert_with(Instant::now);
                if !h.alerted && since.elapsed() >= after && h.total > after.as_secs_f64() {
                    h.alerted = true;
                    notes.push((format!("{name} is offline"), format!("No video for {} s.", after.as_secs())));
                }
            }
        }
        if self.store.settings.alerts {
            for (t, b) in &notes { desktop_notify(t, b); }
        }
        if let Some((t, _)) = notes.last() { self.toast(t); }
    }

    fn toggle_recording(&mut self, i: usize) {
        if let Some(r) = self.recs.remove(&i) {
            let path = stop_recording(r);
            self.toast(&format!("Recording saved: {}", path.display()));
        } else if let Some(cam) = self.store.camera.get(i) {
            match start_recording(cam) {
                Some(r) => { self.recs.insert(i, r); self.toast("Recording started"); }
                None => self.toast("Could not start recording (ffmpeg?)"),
            }
        }
    }

    fn save(&mut self) {
        match self.store.save() {
            Ok(_) => self.toast("Saved"),
            Err(e) => self.toast(&format!("Save error: {e}")),
        }
    }
    fn toast(&mut self, t: &str) { self.toast = Some((t.into(), Instant::now())); }

    fn logout(&mut self) {
        for (_, r) in self.recs.drain() { stop_recording(r); }
        self.hd = None;
        self.streams.clear();
        self.logged_in = false;
        self.login_pass.clear();
        self.editor = None;
    }

    // ── Οθόνη σύνδεσης / πρώτης ρύθμισης ──
    fn login_ui(&mut self, ctx: &egui::Context) {
        let first = self.store.admin_hash.is_empty();
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::TRANSPARENT)).show(ctx, |ui| {
            let card = egui::Rect::from_center_size(ui.max_rect().center(), egui::vec2(360.0, if first { 360.0 } else { 300.0 }));
            ui.allocate_ui_at_rect(card, |ui| {
                egui::Frame::none().fill(glass()).stroke(Stroke::new(1.0, BORDER())).rounding(26.0)
                    .inner_margin(egui::Margin::same(28.0)).show(ui, |ui| {
                    ui.set_width(card.width() - 56.0);
                    ui.label(RichText::new("●  NVR Viewer").size(22.0).strong());
                    ui.label(RichText::new(if first { "First run – create an admin account" } else { "Sign in to continue" }).color(MUTED()));
                    ui.add_space(14.0);
                    ui.label(RichText::new("Username").color(MUTED()).small());
                    ui.add(egui::TextEdit::singleline(&mut self.login_user).desired_width(f32::INFINITY));
                    ui.label(RichText::new("Password").color(MUTED()).small());
                    let r = ui.add(egui::TextEdit::singleline(&mut self.login_pass).password(true).desired_width(f32::INFINITY));
                    if first {
                        ui.label(RichText::new("Repeat password").color(MUTED()).small());
                        ui.add(egui::TextEdit::singleline(&mut self.login_pass2).password(true).desired_width(f32::INFINITY));
                    }
                    let enter = r.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter));
                    if !self.login_err.is_empty() {
                        ui.label(RichText::new(&self.login_err).color(BAD).small());
                    }
                    ui.add_space(6.0);
                    let go = ui.add_sized([ui.available_width(), 34.0], primary(if first { "Create & sign in" } else { "Sign in" })).clicked();
                    if go || enter {
                        if first {
                            if self.login_user.trim().is_empty() || self.login_pass.len() < 4 {
                                self.login_err = "Password needs at least 4 characters".into();
                            } else if self.login_pass != self.login_pass2 {
                                self.login_err = "Passwords do not match".into();
                            } else {
                                let (u, p) = (self.login_user.trim().to_string(), self.login_pass.clone());
                                self.store.set_admin(&u, &p);
                                self.save();
                                self.enter(ctx);
                            }
                        } else if self.store.check(self.login_user.trim(), &self.login_pass) {
                            self.enter(ctx);
                        } else {
                            self.login_err = "Wrong username or password".into();
                            self.login_pass.clear();
                        }
                    }
                });
            });
        });
    }

    fn enter(&mut self, ctx: &egui::Context) {
        self.logged_in = true;
        self.login_err.clear();
        self.login_pass.clear();
        self.login_pass2.clear();
        self.page = if self.store.camera.is_empty() { Page::Cameras } else { Page::Live };
        self.restart_streams(ctx);
    }

    // ── Πάνω μπάρα ──
    fn top_bar(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::top("top").exact_height(48.0).frame(
            egui::Frame::none().fill(if pal().dark { Color32::from_rgba_unmultiplied(14, 14, 16, 215) } else { PANEL() }).inner_margin(egui::Margin::symmetric(16.0, 8.0)).stroke(Stroke::new(1.0, BORDER()))
        ).show(ctx, |ui| {
            ui.horizontal_centered(|ui| {
                ui.label(RichText::new("● ").color(BAD));
                ui.label(RichText::new("NVR").font(semibold(17.0)).extra_letter_spacing(-0.4));
                ui.add_space(18.0);
                for (p, t) in [(Page::Live, "Live view"), (Page::Cameras, "Cameras"), (Page::Playback, "Playback"), (Page::Settings, "Settings")] {
                    let sel = self.page == p;
                    let txt = if sel { RichText::new(t).color(Color32::WHITE).font(semibold(13.5)) } else { RichText::new(t).color(MUTED()) };
                    let b = egui::Button::new(txt).fill(if sel { ACCENT() } else { Color32::TRANSPARENT }).stroke(Stroke::NONE).rounding(999.0);
                    if ui.add(b).clicked() { self.page = p; }
                }
                if self.page == Page::Live {
                    ui.add_space(10.0);
                    ui.separator();
                    let cur = LAYOUTS[(self.store.settings.layout as usize).min(LAYOUTS.len() - 1)];
                    let mut groups: Vec<String> = self.store.camera.iter().map(|c| c.group.trim().to_string()).filter(|g| !g.is_empty()).collect();
                    groups.sort(); groups.dedup();
                    let gsel = self.store.settings.group.clone();
                    let label = if !gsel.is_empty() && groups.contains(&gsel) { format!("▦  {cur} · {gsel}  ▾") } else { format!("▦  {cur}  ▾") };
                    let mut pick: Option<u32> = None;
                    let mut gpick: Option<String> = None;
                    // ένα μενού: διάταξη + ομάδες (όπως στο κινητό)
                    ui.menu_button(RichText::new(label).color(TEXT()), |ui| {
                        ui.set_min_width(180.0);
                        ui.label(RichText::new("LAYOUT").small().color(MUTED()).extra_letter_spacing(1.2));
                        for (k, name) in LAYOUTS.iter().enumerate() {
                            let sel = self.store.settings.layout == k as u32;
                            if ui.selectable_label(sel, if sel { format!("{name}  ✓") } else { name.to_string() }).clicked() { pick = Some(k as u32); ui.close_menu(); }
                        }
                        if matches!(self.store.settings.layout, 4 | 5 | 7) {
                            ui.label(RichText::new("Click a channel to make it the main one").color(MUTED()).small());
                        }
                        if !groups.is_empty() {
                            ui.separator();
                            ui.label(RichText::new("GROUP").small().color(MUTED()).extra_letter_spacing(1.2));
                            for g in std::iter::once(String::new()).chain(groups.iter().cloned()) {
                                let sel = gsel == g;
                                let n = if g.is_empty() { "All cameras".to_string() } else { g.clone() };
                                if ui.selectable_label(sel, if sel { format!("{n}  ✓") } else { n }).clicked() { gpick = Some(g); ui.close_menu(); }
                            }
                        }
                    });
                    if let Some(g) = gpick { self.store.settings.group = g.clone(); self.settings_draft.group = g; self.solo = None; let _ = self.store.save(); }
                    if let Some(k) = pick {
                        self.store.settings.layout = k;
                        self.settings_draft.layout = k;
                        self.solo = None;
                        let _ = self.store.save();
                    }
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Sign out").clicked() { self.logout(); }
                    if self.store.settings.show_clock {
                        ui.label(RichText::new(chrono::Local::now().format("%H:%M:%S").to_string()).monospace().strong());
                    }
                    ui.label(RichText::new(format!("👤 {}", self.store.admin_user)).color(MUTED()));
                    let online = self.streams.iter().flatten()
                        .filter(|s| s.shared.lock().unwrap().last.map_or(false, |t| t.elapsed() < Duration::from_secs(5))).count();
                    ui.label(RichText::new(format!("{online}/{} online", self.store.camera.len())).color(MUTED()));
                });
            });
        });
    }

    // ── Ζωντανή προβολή ──
    fn live_ui(&mut self, ctx: &egui::Context) {
        ctx.input(|i| {
            let keys = [egui::Key::Num1, egui::Key::Num2, egui::Key::Num3, egui::Key::Num4, egui::Key::Num5, egui::Key::Num6, egui::Key::Num7];
            for (n, k) in keys.iter().enumerate() {
                if i.key_pressed(*k) && n < self.streams.len() { self.solo = Some(n); }
            }
            if i.key_pressed(egui::Key::Escape) || i.key_pressed(egui::Key::Num0) { self.solo = None; }
        });
        // πλήκτρα στην πλήρη προβολή: S στιγμιότυπο, R εγγραφή, Z επαναφορά zoom
        if let Some(i) = self.solo {
            let (snap, rec, rz) = ctx.input(|k| (k.key_pressed(egui::Key::S), k.key_pressed(egui::Key::R), k.key_pressed(egui::Key::Z)));
            if snap { if let Some(c) = self.store.camera.get(i) { snapshot(c, self.inbox.clone(), ctx.clone()); self.toast("Taking snapshot…"); } }
            if rec { self.toggle_recording(i); }
            if rz { self.zoom = 1.0; self.zoom_c = egui::pos2(0.5, 0.5); }
        }
        if ctx.input(|k| k.key_pressed(egui::Key::W)) && !ctx.wants_keyboard_input() { self.chrome ^= true; }
        // #7: στην πλήρη προβολή ανοίγει το κύριο (HD) stream· στο πλέγμα μένει το ελαφρύ
        let want_hd = self.solo.filter(|&i| self.store.settings.auto_hd && self.store.camera.get(i).map_or(false, |c| c.enabled && !c.main_stream));
        if self.hd.as_ref().map(|h| h.0) != want_hd {
            self.hd = want_hd.map(|i| {
                let mut c = self.store.camera[i].clone();
                c.main_stream = true;
                let mut st = self.store.settings.clone();
                st.width = 1920; st.height = 1080; st.fps = st.fps.max(15);
                (i, Stream::start(c.url(), st, ctx.clone()))
            });
        }
        if self.solo.is_none() && (self.zoom != 1.0) { self.zoom = 1.0; self.zoom_c = egui::pos2(0.5, 0.5); }
        if let Some((_, s)) = &mut self.hd {
            let mut g = s.shared.lock().unwrap();
            if g.fresh {
                if let Some(f) = &g.frame {
                    if f.len() == s.w * s.h * 4 {
                        let img = egui::ColorImage::from_rgba_unmultiplied([s.w, s.h], f);
                        match &mut s.tex { Some(t) => t.set(img, egui::TextureOptions::LINEAR), None => s.tex = Some(ctx.load_texture("hd", img, egui::TextureOptions::LINEAR)) }
                    }
                }
                g.fresh = false;
            }
        }
        let (w, h) = (self.store.settings.width, self.store.settings.height);
        let mut motion_events: Vec<usize> = vec![];
        for (i, s) in self.streams.iter_mut().enumerate() {
            let Some(s) = s else { continue };
            let mut g = s.shared.lock().unwrap();
            if g.fresh {
                if let Some(f) = &g.frame {
                    if f.len() == w * h * 4 {
                        // #4 ανίχνευση κίνησης: διαφορά φωτεινότητας σε αραιό δείγμα (κάθε 8ο pixel)
                        if self.store.camera.get(i).map_or(false, |c| c.motion) {
                            let samp: Vec<u8> = f.chunks_exact(4).step_by(8).map(|p| ((p[0] as u16 + 2 * p[1] as u16 + p[2] as u16) / 4) as u8).collect();
                            if s.prev.len() == samp.len() {
                                let changed = samp.iter().zip(&s.prev).filter(|(a, b)| (**a as i16 - **b as i16).abs() > 28).count();
                                // ευαισθησία 1..100 → κατώφλι 6%..0.3% των δειγμάτων
                                let sens = self.store.settings.motion_sens.clamp(1, 100) as f32;
                                let thr = samp.len() as f32 * (0.06 - 0.057 * sens / 100.0);
                                if changed as f32 > thr {
                                    s.motion_at = Some(Instant::now());
                                    if s.motion_note.map_or(true, |t| t.elapsed() > Duration::from_secs(60)) {
                                        s.motion_note = Some(Instant::now());
                                        motion_events.push(i);
                                    }
                                }
                            }
                            s.prev = samp;
                        }
                        let img = egui::ColorImage::from_rgba_unmultiplied([w, h], f);
                        match &mut s.tex {
                            Some(t) => t.set(img, egui::TextureOptions::LINEAR),
                            None => s.tex = Some(ctx.load_texture(format!("ch{i}"), img, egui::TextureOptions::LINEAR)),
                        }
                    }
                }
                g.fresh = false;
            }
        }

        for i in motion_events {
            let name = self.store.camera[i].name.clone();
            if self.store.settings.alerts { desktop_notify(&format!("Motion: {name}"), "Movement detected on this camera."); }
            self.toast(&format!("Motion on {name}"));
        }
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::TRANSPARENT).inner_margin(egui::Margin::same(3.0))).show(ctx, |ui| {
            if self.store.camera.is_empty() {
                ui.centered_and_justified(|ui| {
                    ui.label(RichText::new("No cameras yet. Add some from the Cameras page.").color(MUTED()));
                });
                return;
            }
            let layout = self.store.settings.layout;
            let area = ui.available_rect_before_wrap();
            let ncam = self.store.camera.len();
            let main = self.store.settings.main_ch.min(ncam.saturating_sub(1));
            let (idx, cells): (Vec<usize>, Vec<egui::Rect>) = match self.solo {
                Some(i) => (vec![i], layout_cells(1, 1)),
                None => {
                    let g = self.store.settings.group.clone();
                    let vis = |i: usize| g.is_empty() || self.store.camera[i].group == g;
                    let mut order: Vec<usize> = if vis(main) { vec![main] } else { vec![] };
                    order.extend((0..ncam).filter(|&i| i != main && vis(i)));
                    let cells = layout_cells(layout, order.len().max(1));
                    (order, cells)
                }
            };
            let gap = 3.0;
            let mut clicked_main: Option<usize> = None;
            let mut toggle_rec: Option<usize> = None;
            for (k, &i) in idx.iter().enumerate() {
                let Some(u) = cells.get(k) else { break };
                let cell = egui::Rect::from_min_max(
                    area.min + egui::vec2(u.min.x * area.width(), u.min.y * area.height()),
                    area.min + egui::vec2(u.max.x * area.width(), u.max.y * area.height())).shrink(gap / 2.0);
                let resp = ui.interact(cell, ui.id().with(("cell", i)), egui::Sense::click());
                if resp.double_clicked() { self.solo = if self.solo.is_some() { None } else { Some(i) }; }
                else if resp.clicked() && self.solo.is_none() && matches!(layout, 4 | 5 | 7) { clicked_main = Some(i); }
                let p = ui.painter_at(cell);
                p.rect_filled(cell, 14.0, Color32::from_rgb(5, 6, 10));
                let cam = &self.store.camera[i];
                let mut stamp: Option<String> = None;
                let (status, live) = match &self.streams.get(i).and_then(|s| s.as_ref()) {
                    Some(s) => {
                        // στην πλήρη προβολή προτιμάται η HD ροή μόλις έχει εικόνα
                        let hd_tex = self.hd.as_ref().filter(|h| h.0 == i && self.solo.is_some()).and_then(|h| h.1.tex.as_ref().map(|t| (t.id(), h.1.w, h.1.h)));
                        let tex = hd_tex.or_else(|| s.tex.as_ref().map(|t| (t.id(), w, h)));
                        if let Some((tid, tw, th)) = tex {
                            // πλέγμα: ομοιόμορφο γέμισμα κελιού (crop) · πλήρης προβολή: ολόκληρη εικόνα + zoom
                            let ar = tw as f32 / th as f32;
                            let car = cell.width() / cell.height();
                            if self.solo.is_some() {
                                // γεμίζει όλη την περιοχή (cover)· ό,τι περισσεύει κόβεται με το uv
                                let img_rect = cell;
                                let (cw, chh) = if car > ar { (1.0, ar / car) } else { (car / ar, 1.0) };
                                // #5 zoom: ροδέλα γύρω από τον κέρσορα, σύρσιμο για μετακίνηση
                                let zr = ui.interact(img_rect, ui.id().with("zoom"), egui::Sense::drag());
                                if zr.hovered() {
                                    let scroll = ui.input(|k| k.smooth_scroll_delta.y);
                                    if scroll != 0.0 {
                                        let nz = (self.zoom * (1.0 + scroll * 0.0025)).clamp(1.0, 8.0);
                                        if let Some(mp) = zr.hover_pos() {
                                            let rel = (mp - img_rect.min) / img_rect.size();
                                            let half = 0.5 / self.zoom;
                                            let pt = egui::pos2(self.zoom_c.x - half + rel.x / self.zoom, self.zoom_c.y - half + rel.y / self.zoom);
                                            self.zoom = nz;
                                            let nh = 0.5 / nz;
                                            self.zoom_c = egui::pos2(pt.x - rel.x / nz + nh, pt.y - rel.y / nz + nh);
                                        } else { self.zoom = nz; }
                                    }
                                }
                                if zr.dragged() && self.zoom > 1.0 {
                                    let d = zr.drag_delta() / img_rect.size() / self.zoom;
                                    self.zoom_c -= d;
                                }
                                let (hx, hy) = (0.5 * cw / self.zoom, 0.5 * chh / self.zoom);
                                self.zoom_c.x = self.zoom_c.x.clamp(hx, 1.0 - hx);
                                self.zoom_c.y = self.zoom_c.y.clamp(hy, 1.0 - hy);
                                let uv = egui::Rect::from_min_max(egui::pos2(self.zoom_c.x - hx, self.zoom_c.y - hy), egui::pos2(self.zoom_c.x + hx, self.zoom_c.y + hy));
                                p.image(tid, img_rect, uv, Color32::WHITE);
                            } else {
                                let uv = if car > ar { let v = ar / car; egui::Rect::from_min_max(egui::pos2(0.0, (1.0 - v) / 2.0), egui::pos2(1.0, (1.0 + v) / 2.0)) }
                                         else { let u = car / ar; egui::Rect::from_min_max(egui::pos2((1.0 - u) / 2.0, 0.0), egui::pos2((1.0 + u) / 2.0, 1.0)) };
                                let mut rs = egui::epaint::RectShape::filled(cell, 14.0, Color32::WHITE); rs.fill_texture_id = tid; rs.uv = uv; p.add(rs);
                            }
                        }
                        let g = s.shared.lock().unwrap();
                        stamp = g.last_wall.map(|t| t.format("%H:%M:%S").to_string());
                        (g.status.clone(), g.last.map_or(false, |t| t.elapsed() < Duration::from_secs(5)))
                    }
                    None => ("Disabled".into(), false),
                };
                if self.store.settings.show_clock {
                    if let Some(ts) = &stamp {
                        let txt = if live { format!("LIVE {ts}") } else { format!("FROZEN {ts}") };
                        let gal = p.layout_no_wrap(txt, egui::FontId::monospace(10.5), if live { Color32::from_white_alpha(230) } else { BAD });
                        let r = egui::Rect::from_min_size(cell.right_bottom() - gal.size() - egui::vec2(26.0, 14.0), gal.size() + egui::vec2(20.0, 6.0));
                        p.rect_filled(r, 999.0, Color32::from_black_alpha(140));
                        p.circle_filled(r.min + egui::vec2(8.0, r.height() / 2.0), 2.5, if live { OK } else { BAD });
                        p.galley(r.min + egui::vec2(14.0, 3.0), gal, Color32::WHITE);
                    }
                }
                // ετικέτα καναλιού
                let label = format!("CH{}  {}", i + 1, cam.name);
                let galley = p.layout_no_wrap(label, egui::FontId::proportional(13.0), TEXT());
                let pill = egui::Rect::from_min_size(cell.min + egui::vec2(8.0, 8.0), galley.size() + egui::vec2(30.0, 10.0));
                p.rect_filled(pill, 999.0, Color32::from_black_alpha(170));
                p.circle_filled(pill.min + egui::vec2(12.0, pill.height() / 2.0), 4.0, if live { OK } else { BAD });
                p.galley(pill.min + egui::vec2(22.0, 5.0), galley, TEXT());
                if !status.is_empty() && !live {
                    p.text(cell.center(), egui::Align2::CENTER_CENTER, status, egui::FontId::proportional(15.0), MUTED());
                }
                // ένδειξη κίνησης (2 δευτερόλεπτα)
                if self.streams.get(i).and_then(|s| s.as_ref()).and_then(|s| s.motion_at).map_or(false, |t| t.elapsed() < Duration::from_secs(2)) {
                    p.rect_stroke(cell.shrink(1.0), 14.0, Stroke::new(2.0, Color32::from_rgb(255, 176, 32)));
                    let gal = p.layout_no_wrap("MOTION".into(), egui::FontId::monospace(10.0), Color32::BLACK);
                    let rr = egui::Rect::from_min_size(egui::pos2(cell.left() + 10.0, cell.bottom() - 30.0), gal.size() + egui::vec2(14.0, 8.0));
                    p.rect_filled(rr, 999.0, Color32::from_rgb(255, 176, 32));
                    p.galley(rr.min + egui::vec2(7.0, 4.0), gal, Color32::BLACK);
                }
                // ένδειξη εγγραφής
                if let Some(r) = self.recs.get(&i) {
                    let secs = r.started.elapsed().as_secs();
                    let gal = p.layout_no_wrap(format!("REC {:02}:{:02}", secs / 60, secs % 60), egui::FontId::monospace(11.0), Color32::WHITE);
                    let rr = egui::Rect::from_min_size(egui::pos2(cell.right() - gal.size().x - 34.0, cell.top() + 8.0), gal.size() + egui::vec2(24.0, 8.0));
                    p.rect_filled(rr, 999.0, BAD.gamma_multiply(0.85));
                    p.circle_filled(rr.min + egui::vec2(9.0, rr.height() / 2.0), 3.0, Color32::WHITE);
                    p.galley(rr.min + egui::vec2(16.0, 4.0), gal, Color32::WHITE);
                }
                if self.solo == Some(i) {
                    let hd_on = self.hd.as_ref().map_or(false, |h| h.0 == i && h.1.tex.is_some());
                    let mut bar = egui::Rect::from_min_size(egui::pos2(cell.center().x - 210.0, cell.bottom() - 52.0), egui::vec2(420.0, 40.0));
                    if bar.left() < cell.left() { bar = bar.translate(egui::vec2(cell.left() - bar.left(), 0.0)); }
                    ui.allocate_ui_at_rect(bar, |ui| {
                        egui::Frame::none().fill(Color32::from_black_alpha(170)).rounding(999.0).inner_margin(egui::Margin::symmetric(10.0, 5.0)).show(ui, |ui| {
                            ui.horizontal_centered(|ui| {
                                if ui.add(egui::Button::new("📷  Snapshot").rounding(999.0)).on_hover_text("S").clicked() {
                                    snapshot(cam, self.inbox.clone(), ctx.clone());
                                    self.toast = Some(("Taking snapshot…".into(), Instant::now()));
                                }
                                let recording = self.recs.contains_key(&i);
                                let rb = egui::Button::new(RichText::new(if recording { "■  Stop" } else { "⏺  Record" }).color(if recording { Color32::WHITE } else { TEXT() }))
                                    .fill(if recording { BAD } else { SURFACE() }).rounding(999.0);
                                if ui.add(rb).on_hover_text("R").clicked() { toggle_rec = Some(i); }
                                if self.zoom > 1.0 && ui.add(egui::Button::new(format!("{:.1}×  reset", self.zoom)).rounding(999.0)).on_hover_text("Z").clicked() {
                                    self.zoom = 1.0; self.zoom_c = egui::pos2(0.5, 0.5);
                                }
                                ui.label(RichText::new(if hd_on { "HD" } else { "SD" }).monospace().color(if hd_on { OK } else { MUTED() }));
                            });
                        });
                    });
                }
                if resp.hovered() && self.solo.is_none() {
                    p.rect_stroke(cell, 14.0, Stroke::new(1.0, ACCENT().gamma_multiply(0.6)));
                }
            }
            if let Some(i) = toggle_rec.take() { self.toggle_recording(i); }
            if let Some(m) = clicked_main {
                if m != self.store.settings.main_ch {
                    self.store.settings.main_ch = m;
                    self.settings_draft.main_ch = m;
                    let _ = self.store.save();
                }
            }
        });
    }

    // ── Διαχείριση καμερών ──
    fn cameras_ui(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::TRANSPARENT).inner_margin(egui::Margin::same(24.0))).show(ctx, |ui| {
            ui.horizontal(|ui| {
                screen_title(ui, "Manage", "Cameras");
                ui.label(RichText::new(format!("{}/{MAX_CH}", self.store.camera.len())).color(MUTED()));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let can = self.store.camera.len() < MAX_CH;
                    let add = ui.add_enabled(can, primary("+ Add camera")).clicked();
                    if ui.add_enabled(can, egui::Button::new("🗄 Add NVR")).clicked() {
                        self.nvr = Some(NvrForm { name: "NVR".into(), cam: Camera { port: 554, ..Default::default() }, first: 1,
                            count: (MAX_CH - self.store.camera.len()) as u32, show_pass: false,
                            test: Arc::new(Mutex::new(None)), testing: false, err: String::new() });
                    }
                    if ui.add_enabled(!self.store.camera.is_empty(), egui::Button::new("📱 Send to phone")).on_hover_text("Encrypted QR code for the NVRDeck Android app").clicked() {
                        self.qr = Some(QrState { pass: String::new(), pass2: String::new(), tex: None, err: String::new(), n: 0 });
                    }
                    if ui.add_enabled(can, egui::Button::new("🔍 Scan network")).clicked() {
                        self.scan_open = true;
                        if !self.scan.lock().unwrap().running { start_scan(self.scan.clone(), ctx.clone()); }
                    }
                    if add {
                        self.editor = Some(Editor { idx: None, cam: Camera::default(), show_pass: false,
                                                    test: Arc::new(Mutex::new(None)), testing: false, err: String::new() });
                    }
                });
            });
            ui.add_space(12.0);
            if self.store.camera.is_empty() {
                ui.label(RichText::new("No cameras yet. Click Add camera.").color(MUTED()));
            }
            let mut action: Option<(usize, &str)> = None;
            for (i, c) in self.store.camera.iter().enumerate() {
                let live = self.streams.get(i).and_then(|s| s.as_ref())
                    .map_or(false, |s| s.shared.lock().unwrap().last.map_or(false, |t| t.elapsed() < Duration::from_secs(5)));
                egui::Frame::none().fill(glass()).stroke(Stroke::new(1.0, BORDER())).rounding(18.0).inner_margin(egui::Margin::symmetric(16.0, 12.0)).show(ui, |ui| {
                    ui.horizontal(|ui| {
                        let (dot, _) = ui.allocate_exact_size(egui::vec2(10.0, 10.0), egui::Sense::hover());
                        ui.painter().circle_filled(dot.center(), 4.0, if !c.enabled { MUTED() } else if live { OK } else { BAD });
                        ui.vertical(|ui| {
                            ui.label(RichText::new(format!("CH{}  {}", i + 1, c.name)).strong());
                            ui.label(RichText::new(format!("{} · {}:{}{} · {}", c.proto.label(), c.host, c.port,
                                if c.channel > 1 || c.name.starts_with("NVR") { format!(" · channel {}", c.channel) } else { String::new() },
                                if c.main_stream { "main stream" } else { "sub stream" })).color(MUTED()).small());
                            // #11 στατιστικά υγείας
                            if let Some(Some(st)) = self.streams.get(i) {
                                let g = st.shared.lock().unwrap();
                                let up = self.health.get(i).filter(|h| h.total > 1.0).map(|h| format!("{:.1}% uptime", 100.0 * h.online / h.total)).unwrap_or_else(|| "–".into());
                                let mut tags = vec![up, format!("{:.1} fps", g.fps)];
                                if !g.src_res.is_empty() { tags.push(g.src_res.clone()); }
                                tags.push(format!("{} reconnects", g.reconnects));
                                if !c.group.is_empty() { tags.push(format!("group: {}", c.group)); }
                                if c.motion { tags.push("motion alerts".into()); }
                                ui.label(RichText::new(tags.join(" · ")).monospace().size(11.0).color(MUTED()));
                            }
                        });
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui.add(danger("Delete")).clicked() { action = Some((i, "del")); }
                            if ui.button("Edit").clicked() { action = Some((i, "edit")); }
                            if ui.button(if c.enabled { "Disable" } else { "Enable" }).clicked() { action = Some((i, "toggle")); }
                        });
                    });
                });
            }
            match action {
                Some((i, "edit")) => self.editor = Some(Editor { idx: Some(i), cam: self.store.camera[i].clone(), show_pass: false,
                                                                  test: Arc::new(Mutex::new(None)), testing: false, err: String::new() }),
                Some((i, "del")) => self.confirm_delete = Some(i),
                Some((i, "toggle")) => {
                    self.store.camera[i].enabled ^= true;
                    self.save();
                    self.restart_streams(ctx);
                }
                _ => {}
            }
        });

        if let Some(i) = self.confirm_delete {
            let name = self.store.camera.get(i).map(|c| c.name.clone()).unwrap_or_default();
            let mut close = false;
            egui::Window::new("Delete camera").collapsible(false).resizable(false)
                .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0)).show(ctx, |ui| {
                ui.label(format!("Delete camera \"{name}\"?"));
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    if ui.button("Cancel").clicked() { close = true; }
                    if ui.add(egui::Button::new(RichText::new("Delete").color(Color32::WHITE)).fill(BAD)).clicked() {
                        self.store.camera.remove(i);
                        self.save();
                        self.restart_streams(ctx);
                        close = true;
                    }
                });
            });
            if close { self.confirm_delete = None; }
        }

        self.scan_ui(ctx);
        self.qr_ui(ctx);
        self.nvr_ui(ctx);
        self.editor_ui(ctx);
    }


    fn scan_ui(&mut self, ctx: &egui::Context) {
        if !self.scan_open || self.editor.is_some() { return; }
        let mut open = true;
        let mut add: Option<Found> = None;
        let mut rescan = false;
        egui::Window::new("Scan network").open(&mut open).collapsible(false).resizable(false).min_width(560.0)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0)).show(ctx, |ui| {
            let g = self.scan.lock().unwrap();
            ui.label(RichText::new(format!("Subnets: {}", g.subnets.iter().map(|n| format!("{n}.0/24")).collect::<Vec<_>>().join(", "))).color(MUTED()).small());
            if g.running {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(RichText::new(format!("Scanning… {}/{}", g.done, g.total)).color(MUTED()));
                });
                ui.add(egui::ProgressBar::new(if g.total > 0 { g.done as f32 / g.total as f32 } else { 0.0 }).desired_height(4.0));
            } else {
                ui.label(RichText::new(format!("Found {} RTSP devices", g.found.len())).color(MUTED()));
            }
            ui.add_space(6.0);
            egui::ScrollArea::vertical().max_height(360.0).show(ui, |ui| {
                for f in &g.found {
                    let added = self.store.camera.iter().any(|c| c.host == f.host);
                    egui::Frame::none().fill(glass()).stroke(Stroke::new(1.0, BORDER())).rounding(14.0).inner_margin(egui::Margin::symmetric(14.0, 10.0)).show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.vertical(|ui| {
                                ui.label(RichText::new(&f.host).strong().monospace());
                                let mut meta = format!("{} · RTSP :{}", f.vendor, f.rtsp_port);
                                if f.onvif { meta.push_str(" · ONVIF"); }
                                if !f.mac.is_empty() { meta.push_str(&format!(" · {}", f.mac)); }
                                ui.label(RichText::new(meta).color(MUTED()).small());
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if added {
                                    ui.label(RichText::new("✓ Added").color(OK));
                                } else if ui.add_enabled(self.store.camera.len() < MAX_CH, primary("Add")).clicked() {
                                    add = Some(f.clone());
                                }
                            });
                        });
                    });
                }
            });
            ui.add_space(6.0);
            ui.label(RichText::new("Note: the NVR itself may also appear if it has RTSP open.").color(MUTED()).small());
            if !g.running && ui.button("Scan again").clicked() { rescan = true; }
        });
        if rescan { start_scan(self.scan.clone(), ctx.clone()); }
        if let Some(f) = add {
            let n = self.store.camera.len() + 1;
            let cam = Camera { name: format!("Camera {n} (.{})", f.host.rsplit('.').next().unwrap_or("")), host: f.host,
                               port: f.rtsp_port, proto: f.proto, ..Default::default() };
            self.editor = Some(Editor { idx: None, cam, show_pass: false, test: Arc::new(Mutex::new(None)), testing: false, err: String::new() });
        }
        if !open { self.scan_open = false; }
    }


    fn nvr_ui(&mut self, ctx: &egui::Context) {
        let free = MAX_CH.saturating_sub(self.store.camera.len()) as u32;
        let Some(f) = &mut self.nvr else { return };
        let mut close = false;
        let mut create = false;
        egui::Window::new("Add NVR").collapsible(false).resizable(false).min_width(440.0)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0)).show(ctx, |ui| {
            ui.label(RichText::new("Adds the NVR channels as separate cameras (via the NVR's RTSP).").color(MUTED()).small());
            ui.add_space(6.0);
            egui::Grid::new("nvrform").num_columns(2).spacing([14.0, 10.0]).show(ui, |ui| {
                ui.label(RichText::new("Name").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut f.name).desired_width(240.0));
                ui.end_row();
                ui.label(RichText::new("Type").color(MUTED()));
                egui::ComboBox::from_id_salt("nvrproto").selected_text(f.cam.proto.label()).width(240.0).show_ui(ui, |ui| {
                    for p in [Proto::XMeye, Proto::Hikvision, Proto::Dahua] { ui.selectable_value(&mut f.cam.proto, p, p.label()); }
                });
                ui.end_row();
                ui.label(RichText::new("IP / Host").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut f.cam.host).hint_text("e.g. 192.168.1.50").desired_width(240.0));
                ui.end_row();
                ui.label(RichText::new("RTSP port").color(MUTED()));
                ui.add(egui::DragValue::new(&mut f.cam.port).range(1..=65535));
                ui.end_row();
                ui.label(RichText::new("Username").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut f.cam.user).desired_width(240.0));
                ui.end_row();
                ui.label(RichText::new("Password").color(MUTED()));
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut f.cam.pass).password(!f.show_pass).desired_width(194.0));
                    if ui.small_button(if f.show_pass { "🙈" } else { "👁" }).clicked() { f.show_pass ^= true; }
                });
                ui.end_row();
                ui.label(RichText::new("Channels").color(MUTED()));
                ui.horizontal(|ui| {
                    ui.label(RichText::new("from").color(MUTED()).small());
                    ui.add(egui::DragValue::new(&mut f.first).range(1..=64));
                    ui.label(RichText::new("count").color(MUTED()).small());
                    ui.add(egui::DragValue::new(&mut f.count).range(1..=free.max(1)));
                });
                ui.end_row();
                ui.label(RichText::new("Stream").color(MUTED()));
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut f.cam.main_stream, false, "Sub (light)");
                    ui.selectable_value(&mut f.cam.main_stream, true, "Main (HD)");
                });
                ui.end_row();
            });
            f.cam.channel = f.first;
            ui.label(RichText::new(format!("Free slots: {free}")).color(MUTED()).small());
            if let Some(r) = f.test.lock().unwrap().clone() {
                f.testing = false;
                match r {
                    Ok(t) => ui.label(RichText::new(format!("✓ Channel {}: {t}", f.first)).color(OK)),
                    Err(t) => ui.label(RichText::new(format!("✕ Channel {}: {t}", f.first)).color(BAD)),
                };
            } else if f.testing {
                ui.horizontal(|ui| { ui.spinner(); ui.label(RichText::new("Testing connection…").color(MUTED())); });
            }
            if !f.err.is_empty() { ui.label(RichText::new(&f.err).color(BAD)); }
            ui.separator();
            ui.horizontal(|ui| {
                if ui.add_enabled(!f.testing, egui::Button::new("Test first channel")).clicked() {
                    if f.cam.host.trim().is_empty() { f.err = "Required: IP / Host".into(); }
                    else { f.err.clear(); *f.test.lock().unwrap() = None; f.testing = true; probe(f.cam.url(), f.test.clone(), ctx.clone()); }
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.add(primary(&format!("Add {} channels", f.count.min(free)))).clicked() {
                        if f.cam.host.trim().is_empty() { f.err = "Required: IP / Host".into(); }
                        else if free == 0 { f.err = format!("You already have {MAX_CH} cameras").into(); }
                        else { create = true; }
                    }
                    if ui.button("Cancel").clicked() { close = true; }
                });
            });
        });
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) { close = true; }
        if create {
            let f = self.nvr.take().unwrap();
            let name = if f.name.trim().is_empty() { "NVR".to_string() } else { f.name.trim().to_string() };
            for k in 0..f.count.min(free) {
                let mut c = f.cam.clone();
                c.host = c.host.trim().into();
                c.channel = f.first + k;
                c.name = format!("{name} CH{}", c.channel);
                self.store.camera.push(c);
            }
            self.save();
            self.restart_streams(ctx);
        } else if close {
            self.nvr = None;
        }
    }

    fn editor_ui(&mut self, ctx: &egui::Context) {
        let Some(ed) = &mut self.editor else { return };
        let mut close = false;
        let mut save = false;
        let title = if ed.idx.is_some() { "Edit camera" } else { "New camera" };
        egui::Window::new(title).collapsible(false).resizable(false).min_width(420.0)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0)).show(ctx, |ui| {
            let c = &mut ed.cam;
            egui::Grid::new("camform").num_columns(2).spacing([14.0, 10.0]).show(ui, |ui| {
                ui.label(RichText::new("Name").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut c.name).hint_text("e.g. Yard").desired_width(260.0));
                ui.end_row();

                ui.label(RichText::new("Group").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut c.group).hint_text("optional, e.g. Outside").desired_width(260.0));
                ui.end_row();

                ui.label(RichText::new("Motion").color(MUTED()));
                ui.checkbox(&mut c.motion, "Detect motion and alert");
                ui.end_row();

                ui.label(RichText::new("Type").color(MUTED()));
                let before = c.proto;
                egui::ComboBox::from_id_salt("proto").selected_text(c.proto.label()).width(260.0).show_ui(ui, |ui| {
                    for p in Proto::ALL { ui.selectable_value(&mut c.proto, p, p.label()); }
                });
                if before != c.proto && c.port == before.default_port() { c.port = c.proto.default_port(); }
                ui.end_row();

                ui.label(RichText::new("IP / Host").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut c.host).hint_text("e.g. 192.168.1.10").desired_width(260.0));
                ui.end_row();

                ui.label(RichText::new("RTSP port").color(MUTED()));
                ui.add(egui::DragValue::new(&mut c.port).range(1..=65535));
                ui.end_row();

                if c.proto == Proto::Custom {
                    ui.label(RichText::new("Path").color(MUTED()));
                    ui.add(egui::TextEdit::singleline(&mut c.path).hint_text("live1.264").desired_width(260.0));
                    ui.end_row();
                }

                if matches!(c.proto, Proto::XMeye | Proto::Hikvision | Proto::Dahua) {
                    ui.label(RichText::new("Channel").color(MUTED()));
                    ui.horizontal(|ui| {
                        ui.add(egui::DragValue::new(&mut c.channel).range(1..=64));
                        ui.label(RichText::new("1 for a camera, or the NVR channel number").color(MUTED()).small());
                    });
                    ui.end_row();
                }

                ui.label(RichText::new("Username").color(MUTED()));
                ui.add(egui::TextEdit::singleline(&mut c.user).desired_width(260.0));
                ui.end_row();

                ui.label(RichText::new("Password").color(MUTED()));
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(&mut c.pass).password(!ed.show_pass).desired_width(214.0));
                    if ui.small_button(if ed.show_pass { "🙈" } else { "👁" }).clicked() { ed.show_pass ^= true; }
                });
                ui.end_row();

                ui.label(RichText::new("Stream").color(MUTED()));
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut c.main_stream, false, "Sub (light)");
                    ui.selectable_value(&mut c.main_stream, true, "Main (HD)");
                });
                ui.end_row();
            });
            ui.add_space(4.0);
            ui.label(RichText::new(c.safe_url()).color(MUTED()).small().monospace());
            ui.add_space(6.0);

            // αποτέλεσμα δοκιμής
            if let Some(r) = ed.test.lock().unwrap().clone() {
                ed.testing = false;
                match r {
                    Ok(t) => ui.label(RichText::new(format!("✓ {t}")).color(OK)),
                    Err(t) => ui.label(RichText::new(format!("✕ {t}")).color(BAD)),
                };
            } else if ed.testing {
                ui.horizontal(|ui| { ui.spinner(); ui.label(RichText::new("Testing connection…").color(MUTED())); });
            }

            ui.add_space(6.0);
            ui.separator();
            if !ed.err.is_empty() { ui.label(RichText::new(&ed.err).color(BAD)); }
            ui.horizontal(|ui| {
                let missing = |c: &Camera, need_name: bool| -> String {
                    let mut m = vec![];
                    if need_name && c.name.trim().is_empty() { m.push("Name"); }
                    if c.host.trim().is_empty() { m.push("IP / Host"); }
                    if m.is_empty() { String::new() } else { format!("Required: {}", m.join(", ")) }
                };
                if ui.add_enabled(!ed.testing, egui::Button::new("Test connection")).clicked() {
                    ed.err = missing(c, false);
                    if ed.err.is_empty() {
                        *ed.test.lock().unwrap() = None;
                        ed.testing = true;
                        probe(c.url(), ed.test.clone(), ctx.clone());
                    }
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.add(primary("Save")).clicked() {
                        ed.err = missing(c, true);
                        if ed.err.is_empty() { save = true; }
                    }
                    if ui.button("Cancel").clicked() { close = true; }
                });
            });
        });
        if ctx.input(|i| i.key_pressed(egui::Key::Escape)) { close = true; }
        if save {
            let ed = self.editor.take().unwrap();
            let mut cam = ed.cam;
            cam.name = cam.name.trim().into();
            cam.host = cam.host.trim().into();
            match ed.idx { Some(i) => self.store.camera[i] = cam, None => self.store.camera.push(cam) }
            self.save();
            self.restart_streams(ctx);
        } else if close {
            self.editor = None;
        }
    }

    // ── Ρυθμίσεις: κεντραρισμένες κάρτες σε δύο στήλες (μία σε στενό παράθυρο) ──
    fn settings_ui(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::TRANSPARENT).inner_margin(egui::Margin::symmetric(24.0, 20.0))).show(ctx, |ui| {
            egui::ScrollArea::vertical().auto_shrink([false, false]).show(ui, |ui| {
                let maxw = 1120.0_f32.min(ui.available_width());
                let pad = ((ui.available_width() - maxw) / 2.0).max(0.0);
                ui.horizontal_top(|ui| {
                    ui.add_space(pad);
                    ui.vertical(|ui| {
                        ui.set_width(maxw);
                        screen_title(ui, "Apply", "Settings");
                        ui.add_space(16.0);
                        if maxw > 820.0 {
                            ui.columns(2, |cols| {
                                let (l, r) = cols.split_at_mut(1);
                                self.settings_left(ctx, &mut l[0]);
                                self.settings_right(&mut r[0]);
                            });
                        } else {
                            self.settings_left(ctx, ui);
                            ui.add_space(14.0);
                            self.settings_right(ui);
                        }
                        ui.add_space(18.0);
                        ui.label(RichText::new(format!("Config file: {}", store_path().display())).color(MUTED()).small());
                        ui.label(RichText::new("Keys: 1–7 full view · Esc grid · double-click a channel · S snapshot · R record · wheel zoom, Z reset · W hide menus · F11 fullscreen").color(MUTED()).small());
                    });
                });
            });
        });
    }

    fn card(ui: &mut egui::Ui, title: &str, sub: &str, body: impl FnOnce(&mut egui::Ui)) {
        glass_frame().show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.label(RichText::new(title).font(semibold(15.0)));
            if !sub.is_empty() { ui.label(RichText::new(sub).color(MUTED()).small()); }
            ui.add_space(10.0);
            body(ui);
        });
        ui.add_space(14.0);
    }

    fn settings_left(&mut self, ctx: &egui::Context, ui: &mut egui::Ui) {
        Self::card(ui, "Appearance", "Theme and on-video information.", |ui| {
            ui.horizontal_wrapped(|ui| {
                for (i, (name, p)) in THEMES.iter().enumerate() {
                    let sel = self.store.settings.theme == i as u32;
                    let (r, resp) = ui.allocate_exact_size(egui::vec2(116.0, 64.0), egui::Sense::click());
                    let pt = ui.painter();
                    pt.rect_filled(r, 10.0, p.bg);
                    pt.rect_filled(egui::Rect::from_min_size(r.min, egui::vec2(r.width(), 16.0)), egui::Rounding { nw: 10.0, ne: 10.0, sw: 0.0, se: 0.0 }, p.panel);
                    pt.rect_filled(egui::Rect::from_min_size(r.min + egui::vec2(10.0, 26.0), egui::vec2(40.0, 10.0)), 3.0, p.accent);
                    pt.text(r.left_bottom() + egui::vec2(10.0, -8.0), egui::Align2::LEFT_BOTTOM, *name, egui::FontId::proportional(13.0), p.text);
                    pt.rect_stroke(r, 10.0, if sel { Stroke::new(2.0, ACCENT()) } else { Stroke::new(1.0, BORDER()) });
                    if resp.clicked() && !sel {
                        self.store.settings.theme = i as u32;
                        self.settings_draft.theme = i as u32;
                        apply_theme(ctx, i as u32);
                        let _ = self.store.save();
                    }
                }
            });
            ui.add_space(10.0);
            if ui.checkbox(&mut self.store.settings.show_clock, "Clock with seconds on the video (LIVE badge)").changed() {
                self.settings_draft.show_clock = self.store.settings.show_clock;
                let _ = self.store.save();
            }
        });
        Self::card(ui, "Video quality", "Grid resolution and frame rate. Lower = less CPU.", |ui| {
            let d = &mut self.settings_draft;
            ui.horizontal(|ui| {
                for (w, h, t) in [(640, 360, "360p"), (960, 540, "540p"), (1280, 720, "720p"), (1920, 1080, "1080p")] {
                    if ui.selectable_label(d.width == w, t).clicked() { d.width = w; d.height = h; }
                }
            });
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                ui.label(RichText::new("FPS").color(MUTED()));
                ui.add(egui::Slider::new(&mut d.fps, 2..=25));
            });
            ui.add_space(6.0);
            if ui.checkbox(&mut self.store.settings.auto_hd, "Full view switches to the HD main stream").changed() {
                self.settings_draft.auto_hd = self.store.settings.auto_hd;
                let _ = self.store.save();
            }
            ui.add_space(10.0);
            let dirty = self.settings_draft.width != self.store.settings.width || self.settings_draft.fps != self.store.settings.fps;
            if ui.add_enabled(dirty, primary("Apply")).clicked() {
                self.store.settings.width = self.settings_draft.width;
                self.store.settings.height = self.settings_draft.height;
                self.store.settings.fps = self.settings_draft.fps;
                self.save();
                self.restart_streams(ctx);
            }
        });
    }

    fn settings_right(&mut self, ui: &mut egui::Ui) {
        Self::card(ui, "Alerts", "Desktop notifications while NVRDeck is running.", |ui| {
            let st = &mut self.store.settings;
            let mut ch = ui.checkbox(&mut st.alerts, "Notify when a camera goes offline or detects motion").changed();
            ui.add_space(6.0);
            egui::Grid::new("alerts").num_columns(2).spacing([14.0, 8.0]).show(ui, |ui| {
                ui.label(RichText::new("Offline after").color(MUTED()));
                ch |= ui.add(egui::DragValue::new(&mut st.alert_after).range(10..=3600).suffix(" s")).changed();
                ui.end_row();
                ui.label(RichText::new("Motion sensitivity").color(MUTED()));
                ch |= ui.add(egui::Slider::new(&mut st.motion_sens, 1..=100)).changed();
                ui.end_row();
            });
            ui.label(RichText::new("Turn motion on per camera in Cameras → Edit.").color(MUTED()).small());
            if ch { let _ = self.store.save(); }
        });
        Self::card(ui, "Monitor wall", "Fullscreen without menus (W toggles them). Start with --wall.", |ui| {
            if ui.checkbox(&mut self.store.settings.wall_autologin, "In wall mode, skip the login screen").changed() { let _ = self.store.save(); }
            let mut auto = autostart_enabled();
            if ui.checkbox(&mut auto, "Open in wall mode when I log in to this computer").changed() {
                self.toast = Some((match set_autostart(auto) { Ok(()) => if auto { "Autostart on".into() } else { "Autostart off".into() }, Err(e) => format!("Autostart: {e}") }, Instant::now()));
            }
        });
        Self::card(ui, "Admin account", "Login password for this app.", |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.add(egui::TextEdit::singleline(&mut self.login_pass).password(true).hint_text("New password").desired_width(170.0));
                ui.add(egui::TextEdit::singleline(&mut self.login_pass2).password(true).hint_text("Repeat").desired_width(170.0));
                if ui.button("Change").clicked() {
                    if self.login_pass.len() < 4 { self.toast("Password needs at least 4 characters"); }
                    else if self.login_pass != self.login_pass2 { self.toast("Passwords do not match"); }
                    else {
                        let (u, p) = (self.store.admin_user.clone(), self.login_pass.clone());
                        self.store.set_admin(&u, &p);
                        self.login_pass.clear();
                        self.login_pass2.clear();
                        self.save();
                    }
                }
            });
        });
    }

    fn toast_ui(&mut self, ctx: &egui::Context) {
        let Some((t, at)) = &self.toast else { return };
        let age = at.elapsed().as_secs_f32();
        if age > 2.5 { self.toast = None; return; }
        // γρήγορη είσοδος, γρήγορη έξοδος (opacity μόνο)
        let a = (age / 0.15).min(1.0) * ((2.5 - age) / 0.2).min(1.0);
        egui::Area::new(egui::Id::new("toast")).anchor(egui::Align2::CENTER_BOTTOM, egui::vec2(0.0, -24.0)).show(ctx, |ui| {
            egui::Frame::none().fill(SURFACE().gamma_multiply(a)).stroke(Stroke::new(1.0, BORDER().gamma_multiply(a))).rounding(8.0)
                .inner_margin(egui::Margin::symmetric(14.0, 8.0)).show(ui, |ui| ui.label(RichText::new(t.as_str()).color(TEXT().gamma_multiply(a))));
        });
        ctx.request_repaint();
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _f: &mut eframe::Frame) {
        ambient_glow(ctx);
        if self.logged_in { self.tick_health(); }
        let msgs: Vec<String> = std::mem::take(&mut *self.inbox.lock().unwrap());
        for m in msgs { self.toast(&m); }
        if ctx.input(|i| i.key_pressed(egui::Key::F11)) {
            let fs = ctx.input(|i| i.viewport().fullscreen.unwrap_or(false));
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(!fs));
        }
        if self.wall && !self.logged_in && self.store.settings.wall_autologin && !self.store.admin_hash.is_empty() && self.last_tick.elapsed() < Duration::from_secs(3600 * 24 * 365) && self.streams.is_empty() && self.login_err.is_empty() {
            self.enter(ctx);
        }
        if !self.logged_in {
            self.login_ui(ctx);
        } else {
            if self.chrome || self.page != Page::Live { self.top_bar(ctx); }
            match self.page {
                Page::Live => self.live_ui(ctx),
                Page::Cameras => self.cameras_ui(ctx),
                Page::Playback => self.playback_ui(ctx),
                Page::Settings => self.settings_ui(ctx),
            }
        }
        self.toast_ui(ctx);
        ctx.request_repaint_after(Duration::from_millis(250));
    }
}

fn main() -> anyhow::Result<()> {
    let wall = std::env::args().any(|a| a == "--wall");
    let opts = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default().with_fullscreen(wall).with_inner_size([1280.0, 780.0]).with_min_inner_size([720.0, 480.0]).with_title("NVRDeck").with_icon(std::sync::Arc::new(eframe::icon_data::from_png_bytes(include_bytes!("../assets/icon.png")).unwrap_or_default())),
        ..Default::default()
    };
    eframe::run_native("nvrdeck", opts, Box::new(move |cc| { let mut a = App::new(cc); a.wall = wall; a.chrome = !wall; Ok(Box::new(a)) })).map_err(|e| anyhow::anyhow!("{e}"))
}

impl App {
    // ── #6 Κρυπτογραφημένη εξαγωγή με QR ──
    fn qr_ui(&mut self, ctx: &egui::Context) {
        let Some(q) = &mut self.qr else { return };
        let mut open = true;
        let mut gen = false;
        egui::Window::new("Send cameras to phone").open(&mut open).collapsible(false).resizable(false)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0)).show(ctx, |ui| {
            if let Some(t) = &q.tex {
                ui.label(format!("{} cameras, encrypted. In the Android app: Cameras → Import from QR, then type the passphrase.", q.n));
                ui.add_space(8.0);
                ui.vertical_centered(|ui| { ui.image((t.id(), egui::vec2(420.0, 420.0))); });
                ui.add_space(6.0);
                ui.label(RichText::new("The code contains passwords (encrypted). Close this window when you are done.").color(MUTED()).small());
            } else {
                ui.label("Choose a passphrase. You will type it on the phone to decrypt.");
                ui.add_space(6.0);
                ui.add(egui::TextEdit::singleline(&mut q.pass).password(true).hint_text("Passphrase (8+ characters)").desired_width(300.0));
                ui.add(egui::TextEdit::singleline(&mut q.pass2).password(true).hint_text("Repeat").desired_width(300.0));
                if !q.err.is_empty() { ui.label(RichText::new(&q.err).color(BAD)); }
                ui.add_space(6.0);
                if ui.add(primary("Create QR code")).clicked() { gen = true; }
            }
        });
        if gen {
            if q.pass.chars().count() < 8 { q.err = "Use at least 8 characters".into(); }
            else if q.pass != q.pass2 { q.err = "Passphrases do not match".into(); }
            else {
                let cams: Vec<serde_json::Value> = self.store.camera.iter().map(|c| serde_json::json!({
                    "name": c.name, "host": c.host, "port": c.port, "proto": c.proto.code(), "user": c.user, "pass": c.pass,
                    "channel": c.channel, "mainStream": c.main_stream, "path": c.path, "enabled": c.enabled, "group": c.group,
                })).collect();
                let json = serde_json::json!({ "v": 1, "cameras": cams }).to_string();
                match share::encrypt(json.as_bytes(), &q.pass).and_then(|e| share::qr_image(&e)) {
                    Ok((n, px)) => {
                        let img = egui::ColorImage::from_rgba_unmultiplied([n, n], &px);
                        q.tex = Some(ctx.load_texture("qr", img, egui::TextureOptions::NEAREST));
                        q.n = cams.len();
                        q.pass.clear(); q.pass2.clear(); q.err.clear();
                    }
                    Err(e) => q.err = format!("Could not create code: {e}"),
                }
            }
        }
        if !open { self.qr = None; }
    }

    // ── #8 Αναπαραγωγή τοπικών εγγραφών / στιγμιοτύπων ──
    fn playback_ui(&mut self, ctx: &egui::Context) {
        if self.media.is_empty() || ctx.input(|i| i.time) as u64 % 5 == 0 { self.media = list_media(); }
        egui::CentralPanel::default().frame(egui::Frame::none().fill(Color32::TRANSPARENT).inner_margin(egui::Margin::same(24.0))).show(ctx, |ui| {
            ui.horizontal(|ui| {
                screen_title(ui, "Saved on this computer", "Playback");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Open videos folder").clicked() { open_path(&media_dir(true)); }
                    if ui.button("Open pictures folder").clicked() { open_path(&media_dir(false)); }
                });
            });
            ui.label(RichText::new("Recordings and snapshots made with NVRDeck. NVR-side playback (the recorder's own disk) is not supported yet.").color(MUTED()).small());
            ui.add_space(12.0);
            if self.media.is_empty() {
                ui.label(RichText::new("Nothing yet. In full view press R to record or S for a snapshot.").color(MUTED()));
            }
            egui::ScrollArea::vertical().show(ui, |ui| {
                for (path, video, when, size) in &self.media {
                    egui::Frame::none().fill(glass()).stroke(Stroke::new(1.0, BORDER())).rounding(14.0).inner_margin(egui::Margin::symmetric(14.0, 10.0)).show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.label(RichText::new(if *video { "🎞" } else { "📷" }).size(18.0));
                            ui.vertical(|ui| {
                                ui.label(RichText::new(path.file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default()).strong());
                                let t: chrono::DateTime<chrono::Local> = (*when).into();
                                ui.label(RichText::new(format!("{} · {:.1} MB", t.format("%d/%m/%Y %H:%M:%S"), *size as f64 / 1e6)).color(MUTED()).small());
                            });
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if ui.button(if *video { "▶ Play" } else { "Open" }).clicked() { open_path(path); }
                            });
                        });
                    });
                    ui.add_space(6.0);
                }
            });
        });
    }
}

fn list_media() -> Vec<(PathBuf, bool, std::time::SystemTime, u64)> {
    let mut v = vec![];
    for video in [true, false] {
        if let Ok(rd) = std::fs::read_dir(media_dir(video)) {
            for e in rd.flatten() {
                if let Ok(m) = e.metadata() {
                    if m.is_file() { v.push((e.path(), video, m.modified().unwrap_or(std::time::UNIX_EPOCH), m.len())); }
                }
            }
        }
    }
    v.sort_by(|a, b| b.2.cmp(&a.2));
    v
}

fn open_path(p: &std::path::Path) {
    let _ = std::fs::create_dir_all(if p.extension().is_some() { p.parent().unwrap_or(p) } else { p });
    #[cfg(target_os = "macos")] let cmd = "open";
    #[cfg(not(target_os = "macos"))] let cmd = "xdg-open";
    let _ = Command::new(cmd).arg(p).stdout(Stdio::null()).stderr(Stdio::null()).spawn();
}

// ── #12 Αυτόματη εκκίνηση ──
#[cfg(target_os = "macos")]
fn autostart_file() -> PathBuf { dirs::home_dir().unwrap_or_default().join("Library/LaunchAgents/io.github.genetzhs.nvrdeck.plist") }
#[cfg(not(target_os = "macos"))]
fn autostart_file() -> PathBuf { dirs::config_dir().unwrap_or_default().join("autostart/nvrdeck.desktop") }

fn autostart_enabled() -> bool { autostart_file().exists() }

fn set_autostart(on: bool) -> anyhow::Result<()> {
    let f = autostart_file();
    if !on { if f.exists() { std::fs::remove_file(&f)?; } return Ok(()); }
    // AppImage: το $APPIMAGE δείχνει το ίδιο το αρχείο, όχι το προσωρινό mount
    let exe = std::env::var_os("APPIMAGE").map(PathBuf::from).unwrap_or(std::env::current_exe()?);
    if let Some(d) = f.parent() { std::fs::create_dir_all(d)?; }
    #[cfg(target_os = "macos")]
    let body = format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n<plist version=\"1.0\"><dict><key>Label</key><string>io.github.genetzhs.nvrdeck</string><key>ProgramArguments</key><array><string>{}</string><string>--wall</string></array><key>RunAtLoad</key><true/></dict></plist>\n", exe.display());
    #[cfg(not(target_os = "macos"))]
    let body = format!("[Desktop Entry]\nType=Application\nName=NVRDeck\nExec=\"{}\" --wall\nIcon=nvrdeck\nX-GNOME-Autostart-enabled=true\n", exe.display());
    std::fs::write(&f, body)?;
    Ok(())
}
