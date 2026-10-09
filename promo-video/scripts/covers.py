#!/usr/bin/env python3
"""Create four release covers from the published videos (Python + Pillow + FFmpeg)."""
import argparse
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
INK = '#263B2E'
GREEN = '#28634E'
MUTED = '#657360'
FONT = '/usr/share/fonts/google-noto-sans-cjk-vf-fonts/NotoSansCJK-VF.ttc'


def font(path, size, bold=False):
    result = ImageFont.truetype(str(path), size, index=2)
    if bold:
        result.set_variation_by_name('Bold')
    return result


def frame(video, seconds, output):
    subprocess.run(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error',
                    '-ss', str(seconds), '-i', str(ROOT / 'videos' / video),
                    '-frames:v', '1', str(output)], check=True)
    with Image.open(output) as source:
        return source.convert('RGB')


def panel(canvas, image, box, radius=30):
    x, y, width, height = box
    image = image.resize((width, height), Image.Resampling.LANCZOS)
    mask = Image.new('L', image.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, width - 1, height - 1), radius, fill=255)
    shadow = Image.new('RGBA', canvas.size)
    ImageDraw.Draw(shadow).rounded_rectangle(
        (x + 8, y + 18, x + width + 8, y + height + 18), radius, fill=(38, 59, 46, 45))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(22)))
    canvas.paste(image, (x, y), mask)


def cover(kind, width, promo, tutorial, font_path):
    height = 1080
    compact = width == 1440
    margin = 76 if compact else 104
    canvas = Image.new('RGBA', (width, height), '#F5F7F0')
    glow = Image.new('RGB', canvas.size, '#F5F7F0')
    gd = ImageDraw.Draw(glow)
    gd.ellipse((width * .48, -300, width + 480, 1020), fill='#E1EAD8')
    gd.ellipse((-450, 660, 1000, 1500), fill='#F2EBDD')
    canvas = glow.filter(ImageFilter.GaussianBlur(120)).convert('RGBA')
    d = ImageDraw.Draw(canvas)
    for x in range(int(width * .59), width, 64):
        d.line((x, 150, x, 1000), fill='#DDE5D5', width=1)
    for y in range(170, 1000, 64):
        d.line((int(width * .59), y, width, y), fill='#DDE5D5', width=1)

    def text(x, y, value, size, color=INK, bold=False):
        d.text((x, y), value, font=font(font_path, size, bold), fill=color, anchor='lt')

    icon = Image.open(ROOT.parent / 'design/icon/katadroid-ai-512.png').convert('RGBA')
    icon.thumbnail((64, 64), Image.Resampling.LANCZOS)
    canvas.alpha_composite(icon, (margin, 62))
    text(margin + 82, 76, 'KataDroid', 36, bold=True)
    tag = '产品宣传' if kind == 'promo' else '复盘教程'
    tag_width = 164
    d.rounded_rectangle((width - margin - tag_width, 66, width - margin, 118), 26, fill=GREEN)
    text(width - margin - tag_width + 25, 79, tag, 27, '#FFFEF8', True)

    x = margin
    size = 85 if compact else 110
    right = 825 if compact else 1175
    if kind == 'promo':
        text(x, 242, '随身的围棋分析伙伴', 30, GREEN)
        text(x - 4, 320, '把 KataGo', size, bold=True)
        text(x - 4, 320 + size + 35, '装进口袋', size, GREEN, True)
        text(x, 626, '离线分析 · 随身复盘 · AI 对弈', 29 if compact else 34, MUTED)
        d.rounded_rectangle((x, 735, x + 320, 803), 18, fill=GREEN)
        text(x + 28, 752, 'Android 本地 AI', 32, '#FFFEF8', True)
        phone = promo.crop((1308, 120, 1728, 956))
        pw = 365 if compact else 412
        panel(canvas, phone, (right + 28, 170, pw, round(pw * phone.height / phone.width)), 38)
    else:
        text(x, 242, '跟着转折，学会复盘', 30, GREEN)
        text(x - 4, 320, '一分钟', size, bold=True)
        text(x - 4, 320 + size + 35, '看懂得失', size, GREEN, True)
        text(x, 626, '点曲线回看，长按预览变化', 29 if compact else 34, MUTED)
        d.rounded_rectangle((x, 735, x + (605 if compact else 740), 813), 18, fill='#E2ECD8')
        text(x + 24, 757, '导入 → 分析 → 回看 → 保存', 30 if compact else 36, GREEN, True)
        pw = 490 if compact else 620
        board = tutorial.crop((70, 300, 1014, 826))
        panel(canvas, board, (right, 185, pw, round(pw * board.height / board.width)), 26)
        graph = tutorial.crop((70, 956, 1014, 1510))
        panel(canvas, graph, (right, 505 if compact else 560, pw, round(pw * graph.height / graph.width)), 26)
        text(right + 12, 463 if compact else 536, '实录棋盘 / 黑棋胜率', 22, MUTED)
        d.rounded_rectangle((right + 22, 868 if compact else 932, right + pw - 22, 926 if compact else 990), 16, fill=GREEN)
        text(right + 42, 885 if compact else 949, '102→103 手：79.0% → 46.3%', 24 if compact else 30, '#FFFEF8', True)

    # Panels are composited separately; refresh the drawing context for the footer.
    d = ImageDraw.Draw(canvas)
    d.line((margin, 1017, width - margin, 1017), fill='#BDCDB4', width=2)
    text(margin, 951, '围棋的下一步', 26, GREEN)
    return canvas.convert('RGB')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', type=Path, default=Path(FONT), help='Noto Sans CJK variable TTC')
    parser.add_argument('--output', type=Path, default=ROOT / 'videos')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    scratch = ROOT / '.local/covers'
    scratch.mkdir(parents=True, exist_ok=True)
    promo = frame('katadroid-promo-zh-54s.mp4', 3, scratch / 'promo-frame.png')
    tutorial = frame('katadroid-tutorial-zh-vertical-58s.mp4', 24, scratch / 'tutorial-frame.png')
    for kind in ('promo', 'tutorial'):
        for ratio, width in (('16x9', 1920), ('4x3', 1440)):
            output = args.output / f'katadroid-{kind}-zh-{ratio}.png'
            cover(kind, width, promo, tutorial, args.font).save(output, optimize=True)
            print(output.relative_to(ROOT) if output.is_relative_to(ROOT) else output)


if __name__ == '__main__':
    main()
