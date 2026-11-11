"""Prepare deterministic Android resources from the supplied JA artwork.
Run from the repository root with Pillow installed: python scripts/prepare_icons.py
The source artwork is retained unchanged in branding/ja-icon-original.png.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
original = Image.open(ROOT / 'branding/ja-icon-original.png').convert('RGB')
# Exclude the white presentation surround while keeping the original monogram.
# These coordinates refer to the supplied 1254 x 1254 artwork.
if original.size != (1254, 1254):
    raise ValueError('Expected the supplied 1254 x 1254 source artwork')
art = original.crop((177, 177, 1077, 1077))
for density, size, foreground_size in [
    ('mdpi', 48, 108), ('hdpi', 72, 162), ('xhdpi', 96, 216),
    ('xxhdpi', 144, 324), ('xxxhdpi', 192, 432)
]:
    folder = RES / f'mipmap-{density}'
    folder.mkdir(parents=True, exist_ok=True)
    icon = art.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(folder / 'ic_launcher.png')
    # Legacy round launchers receive an actual circular PNG; adaptive devices
    # receive their own system-provided mask via the v26 XML below.
    round_icon = icon.convert('RGBA')
    mask = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    round_icon.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
    round_icon.save(folder / 'ic_launcher_round.png')
    foreground = Image.new('RGBA', (foreground_size, foreground_size), (0, 0, 0, 0))
    # Safe padding keeps the mark inside circle and squircle launcher masks.
    art_size = round(foreground_size * 64 / 108)
    fg_art = art.resize((art_size, art_size), Image.Resampling.LANCZOS).convert('RGBA')
    margin = (foreground_size - art_size) // 2
    foreground.alpha_composite(fg_art, (margin, margin))
    foreground.save(folder / 'ic_launcher_foreground.png')

adaptive = RES / 'mipmap-anydpi-v26'
adaptive.mkdir(parents=True, exist_ok=True)
xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ('ic_launcher', 'ic_launcher_round'):
    (adaptive / f'{name}.xml').write_text(xml)
values = RES / 'values'
values.mkdir(parents=True, exist_ok=True)
(values / 'icon_colors.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#000000</color>
</resources>
''')
banner = Image.new('RGB', (320, 180), 'black')
banner.paste(art.resize((144, 144), Image.Resampling.LANCZOS), (22, 18))
draw = ImageDraw.Draw(banner)
try:
    font = ImageFont.truetype('DejaVuSans-Bold.ttf', 48)
except OSError:
    font = ImageFont.load_default(size=48)
draw.text((232, 90), 'JA', font=font, fill=(90, 255, 0), anchor='mm')
banner_path = RES / 'drawable-xhdpi'
banner_path.mkdir(parents=True, exist_ok=True)
banner.save(banner_path / 'tv_banner.png')
print('Prepared launcher resources for five densities, adaptive icons and TV banner.')
