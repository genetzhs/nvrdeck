"""Δημιουργεί το εικονίδιο της εφαρμογής (PNG) – ίδιο σχέδιο με το adaptive icon του Android."""
import sys
from PIL import Image, ImageDraw, ImageFilter

def lerp(a, b, t): return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))

def icon(size):
    S = size * 4  # supersampling
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    # στρογγυλεμένο σκούρο τετράγωνο
    bg = Image.new("RGBA", (S, S), (6, 7, 11, 255))
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-S*0.2, -S*0.25, S*0.7, S*0.65], fill=(139, 124, 255, 110))
    gd.ellipse([S*0.45, S*0.5, S*1.2, S*1.25], fill=(94, 230, 255, 60))
    glow = glow.filter(ImageFilter.GaussianBlur(S * 0.12))
    bg.alpha_composite(glow)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.225), fill=255)
    img.paste(bg, (0, 0), mask)
    d = ImageDraw.Draw(img)
    c = S / 2
    # δακτύλιος φακού με gradient βιολετί→κυανό
    ring = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    for i in range(S):
        t = i / S
        ImageDraw.Draw(ring).line([(i, 0), (i, S)], fill=lerp((156, 143, 255, 255), (94, 230, 255, 255), t))
    rmask = Image.new("L", (S, S), 0)
    R = S * 0.30
    ImageDraw.Draw(rmask).ellipse([c - R, c - R, c + R, c + R], fill=255)
    img.paste(ring, (0, 0), rmask)
    r2 = S * 0.225
    d.ellipse([c - r2, c - r2, c + r2, c + r2], fill=(13, 14, 21, 255))
    r3 = S * 0.12
    d.ellipse([c - r3, c - r3, c + r3, c + r3], fill=(110, 98, 220, 255))
    r4 = S * 0.036
    d.ellipse([c - S*0.05 - r4, c - S*0.05 - r4, c - S*0.05 + r4, c - S*0.05 + r4], fill=(255, 255, 255, 230))
    r5 = S * 0.06
    d.ellipse([S*0.71 - r5, S*0.29 - r5, S*0.71 + r5, S*0.29 + r5], fill=(255, 92, 122, 255))
    return img.resize((size, size), Image.LANCZOS)

if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "icon.png"
    icon(int(sys.argv[2]) if len(sys.argv) > 2 else 512).save(out)
