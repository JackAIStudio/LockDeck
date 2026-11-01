"""LockDeck 图标定稿：字母 L。

这个脚本是 res/ 里那三个 vector 的唯一来源——
跑一次就能重新产出 SVG、全部尺寸 PNG、monochrome 层。
改几何请改这里，别只改 XML，否则设计稿和实际图标会分叉。

几何（108dp 画布，自适应图标）
  bbox      x 33..75, y 27..81   → 42 × 54
  笔画宽    21
  外角      9        内角直角（L 的内角本来就该是尖的，圆掉就不像字了）
  安全区    中心 66dp 圆（半径 33）—— 实测最远点 30.6，圆形遮罩不会切笔画
"""
import subprocess, os
from PIL import Image
from Lpath import letter_l

HERE   = os.path.dirname(os.path.abspath(__file__))
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
SVG, PNG = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
os.makedirs(SVG, exist_ok=True); os.makedirs(PNG, exist_ok=True)

INK   = "#1B1E25"
PAPER = "#F2F5FA"

L_PATH = letter_l(33, 27, 75, 81, 21, 9)

def render(body, name, size=1152, transparent=False):
    p = os.path.join(SVG, name + ".svg")
    open(p, "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
        f'viewBox="0 0 108 108">{body}</svg>')
    png = os.path.join(PNG, name + ".png")
    cmd = [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars",
           "--force-device-scale-factor=1", f"--screenshot={png}",
           f"--window-size={size},{size}", f"file://{p}"]
    if transparent:
        cmd.insert(4, "--default-background-color=00000000")
    subprocess.run(cmd, capture_output=True)
    return Image.open(png).convert("RGBA")

color = render(f'<rect width="108" height="108" fill="{INK}"/><path fill="{PAPER}" d="{L_PATH}"/>',
               "D-letterL")
for s in (24, 42, 48, 58, 72, 104, 192, 240, 432):
    color.resize((s, s), Image.LANCZOS).save(f"{PNG}/D-letterL-{s}.png")

mono = render(f'<path fill="#FFFFFF" d="{L_PATH}"/>', "D-letterL-mono", transparent=True)
mono.resize((432, 432), Image.LANCZOS).save(f"{PNG}/D-letterL-mono-432.png")
bg = Image.new("RGB", (432, 432), (27, 30, 37))
m = mono.resize((432, 432), Image.LANCZOS)
bg.paste(m, (0, 0), m)
bg.save(f"{PNG}/D-letterL-mono-preview.png")

print(L_PATH)
