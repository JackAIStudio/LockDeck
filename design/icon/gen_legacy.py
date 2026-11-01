"""生成旧式位图图标（mipmap-*dpi/ic_launcher.webp、ic_launcher_round.webp）。

虽然 minSdk=31 走不到这里（mipmap-anydpi-v26 永远优先），
但工程里留着旧的绿色 webp 迟早有人误用，所以一并换成新的。
圆形版按启动器实际形状裁圆。
"""
import subprocess, os
from PIL import Image, ImageDraw

# 从脚本位置往上找工程根（design/icon/ → design/ → 工程根）
PROJ = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
HERE = os.path.dirname(os.path.abspath(__file__))
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
INK, PAPER = "#1B1E25", "#F2F5FA"
L = "M42,27 h3 a9,9 0 0 1 9,9 v24 h12 a9,9 0 0 1 9,9 v3 a9,9 0 0 1 -9,9 h-24 a9,9 0 0 1 -9,-9 v-36 a9,9 0 0 1 9,-9 z"

def render(body, name, size=1024):
    p = os.path.join(HERE, "tmp", name + ".svg")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 108 108">{body}</svg>')
    png = os.path.join(HERE, "tmp", name + ".png")
    subprocess.run([CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
                    "--force-device-scale-factor=1", f"--screenshot={png}",
                    f"--window-size={size},{size}", f"file://{p}"], capture_output=True)
    return Image.open(png).convert("RGBA")

# 旧图标的内容区比自适应图标小：四周留 ~10% 边距，避免字母贴边
base = render(
    f'<rect width="108" height="108" fill="{INK}"/>'
    f'<g transform="translate(54,54) scale(0.86) translate(-54,-54)">'
    f'<path fill="{PAPER}" d="{L}"/></g>', "legacy")

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

def rounded(img, s, radius_ratio):
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s-1, s-1], radius=int(s*radius_ratio), fill=255)
    out.paste(img.resize((s, s), Image.LANCZOS), (0, 0), mask)
    return out

def circular(img, s):
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, s-1, s-1], fill=255)
    out.paste(img.resize((s, s), Image.LANCZOS), (0, 0), mask)
    return out

for dpi, s in SIZES.items():
    d = os.path.join(PROJ, "app/src/main/res", f"mipmap-{dpi}")
    rounded(base, s, 0.235).save(os.path.join(d, "ic_launcher.webp"), "WEBP", lossless=True)
    circular(base, s).save(os.path.join(d, "ic_launcher_round.webp"), "WEBP", lossless=True)

print("legacy mipmaps regenerated")
