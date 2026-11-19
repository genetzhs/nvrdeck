//! Αναγνώριση ανθρώπων/οχημάτων με YOLOX-nano (Apache 2.0) μέσω tract (καθαρό Rust, CPU).
//! Τρέχει μόνο όταν η απλή ανίχνευση κίνησης δει κάτι, άρα το κόστος CPU είναι μικρό.
use tract_onnx::prelude::*;

const SIZE: usize = 416;
static MODEL: &[u8] = include_bytes!("../assets/models/yolox_nano.onnx");

/// Κλάσεις COCO που μας ενδιαφέρουν
pub fn label(class: usize) -> Option<&'static str> {
    match class { 0 => Some("Person"), 1 => Some("Bicycle"), 2 => Some("Car"), 3 => Some("Motorcycle"), 5 => Some("Bus"), 7 => Some("Truck"), _ => None }
}

type Plan = SimplePlan<TypedFact, Box<dyn TypedOp>, Graph<TypedFact, Box<dyn TypedOp>>>;

pub struct Detector { plan: Plan }

#[derive(Debug, Clone)]
pub struct Hit { pub class: usize, pub score: f32, pub bx: [f32; 4] } // bx: x0,y0,x1,y1 σε 0..1

pub fn iou(a: &[f32; 4], b: &[f32; 4]) -> f32 {
    let ix = (a[2].min(b[2]) - a[0].max(b[0])).max(0.0);
    let iy = (a[3].min(b[3]) - a[1].max(b[1])).max(0.0);
    let i = ix * iy;
    let u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i;
    if u <= 0.0 { 0.0 } else { i / u }
}

impl Detector {
    pub fn new() -> anyhow::Result<Detector> {
        let plan = tract_onnx::onnx().model_for_read(&mut std::io::Cursor::new(MODEL))?
            .with_input_fact(0, f32::fact([1, 3, SIZE, SIZE]).into())?
            .into_optimized()?.into_runnable()?;
        Ok(Detector { plan })
    }

    /// `rgba`: εικόνα w×h. Επιστρέφει ανθρώπους/οχήματα με βαθμό ≥ `min`.
    pub fn run(&self, rgba: &[u8], w: usize, h: usize, min: f32) -> anyhow::Result<Vec<Hit>> {
        // letterbox σε 416×416, γέμισμα 114, BGR, τιμές 0..255 (όπως στο YOLOX)
        let r = (SIZE as f32 / w as f32).min(SIZE as f32 / h as f32);
        let (nw, nh) = ((w as f32 * r) as usize, (h as f32 * r) as usize);
        let mut t = tract_ndarray::Array4::<f32>::from_elem((1, 3, SIZE, SIZE), 114.0);
        for y in 0..nh {
            let sy = ((y as f32 / r) as usize).min(h - 1);
            for x in 0..nw {
                let sx = ((x as f32 / r) as usize).min(w - 1);
                let o = (sy * w + sx) * 4;
                t[[0, 0, y, x]] = rgba[o + 2] as f32;
                t[[0, 1, y, x]] = rgba[o + 1] as f32;
                t[[0, 2, y, x]] = rgba[o] as f32;
            }
        }
        let out = self.plan.run(tvec!(t.into_tensor().into()))?;
        let a = out[0].to_array_view::<f32>()?;
        let n = a.shape()[1];
        // πλέγματα YOLOX: βήματα 8/16/32 → 52², 26², 13² θέσεις
        let mut grid = Vec::with_capacity(n);
        for st in [8usize, 16, 32] { let g = SIZE / st; for gy in 0..g { for gx in 0..g { grid.push((gx as f32, gy as f32, st as f32)); } } }
        let mut hits: Vec<Hit> = vec![];
        for i in 0..n.min(grid.len()) {
            let obj = a[[0, i, 4]];
            if obj < min * 0.5 { continue; }
            let (mut bc, mut bs) = (0, 0f32);
            for c in 0..80 { let v = a[[0, i, 5 + c]]; if v > bs { bs = v; bc = c; } }
            let score = obj * bs;
            if score < min || label(bc).is_none() { continue; }
            let (gx, gy, st) = grid[i];
            let cx = (a[[0, i, 0]] + gx) * st; let cy = (a[[0, i, 1]] + gy) * st;
            let bw = a[[0, i, 2]].exp() * st; let bh = a[[0, i, 3]].exp() * st;
            let (sw, sh) = (w as f32 * r, h as f32 * r);
            let bx = [((cx - bw / 2.0) / sw).clamp(0.0, 1.0), ((cy - bh / 2.0) / sh).clamp(0.0, 1.0), ((cx + bw / 2.0) / sw).clamp(0.0, 1.0), ((cy + bh / 2.0) / sh).clamp(0.0, 1.0)];
            hits.push(Hit { class: bc, score, bx });
        }
        // NMS
        hits.sort_by(|a, b| b.score.partial_cmp(&a.score).unwrap_or(std::cmp::Ordering::Equal));
        let mut best: Vec<Hit> = vec![];
        for h in hits { if !best.iter().any(|k| iou(&k.bx, &h.bx) > 0.45) { best.push(h); } }
        Ok(best)
    }
}
