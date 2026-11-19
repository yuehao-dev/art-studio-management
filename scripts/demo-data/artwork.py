"""Deterministic, original teaching illustrations; no external photos or student work."""
import math
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


def draw_exercise(path, category, seed, revision=1):
    rng = random.Random(seed)
    image = Image.new('RGB', (960, 720), '#f6f3eb')
    d = ImageDraw.Draw(image, 'RGBA')
    d.rectangle((32, 32, 928, 688), outline='#d5d0c5', width=2)
    if category in ('sketch', 'advanced'):
        # Graphite still life, with separate construction and finished versions.
        d.polygon([(110, 567), (740, 610), (863, 496), (304, 469)], fill=(94, 86, 72, 24))
        for x, y, radius in [(315, 404, 104), (647, 466, 68)]:
            for r in range(radius, 0, -2):
                shade = 110 + int((radius-r)/radius*130)
                d.ellipse((x-r, y-r, x+r, y+r), fill=(shade, shade, shade-3, 255))
            d.arc((x-radius, y-radius, x+radius, y+radius), 55, 245, fill='#55534f', width=2)
        d.polygon([(439, 294), (566, 272), (588, 436), (460, 462)], fill='#bcbab4', outline='#5f5d57')
        d.polygon([(439, 294), (476, 253), (605, 232), (566, 272)], fill='#e2dfd6', outline='#77736c')
        d.polygon([(566, 272), (605, 232), (626, 398), (588, 436)], fill='#787772', outline='#5f5d57')
        for _ in range(260 if revision == 2 else 130):
            x, y = rng.randint(140, 810), rng.randint(490, 610)
            d.line((x, y, x+18, y-5), fill=(60, 57, 50, rng.randint(12, 38)), width=1)
        d.line((95, 579, 860, 579), fill='#a6a298', width=1)
    elif category == 'ink':
        for i in range(5):
            x = 230+i*110+rng.randint(-20, 20)
            d.line((x, 590, x-70, 125), fill=(36, 48, 33, 160), width=rng.randint(5, 9))
            for y in [220, 310, 405, 480]:
                bx = x-int((590-y)*70/465)
                d.line((bx-9, y, bx+8, y-2), fill='#253d2a', width=3)
                for side in [-1, 1]:
                    d.polygon([(bx, y), (bx+side*105, y-63), (bx+side*70, y-12)], fill=(35, 66, 43, rng.randint(100, 220)))
        d.rectangle((817, 532, 855, 572), outline='#a74435', width=3)
    elif category == 'creative':
        palette = ['#e7ad59', '#7d9e91', '#e0a59a', '#7798b8', '#b8a0bf']
        for i in range(7):
            x, y = 85+i*111, 320+rng.randint(-110, 100)
            d.line((x, y+20, x-20, 590), fill='#738c63', width=5)
            for a in range(0, 360, 60):
                dx, dy = math.cos(math.radians(a))*37, math.sin(math.radians(a))*37
                d.ellipse((x+dx-29, y+dy-29, x+dx+29, y+dy+29), fill=palette[i%5])
            d.ellipse((x-21, y-21, x+21, y+21), fill='#c88c4d')
        d.polygon([(70, 590), (220, 537), (480, 594), (760, 549), (887, 601), (887, 640), (70, 640)], fill='#b8c4a4')
    else:
        # Translucent washes: an autumn plant observation or an oil landscape study.
        if category == 'oil':
            d.rectangle((90, 130, 870, 560), fill='#cad9da')
            d.polygon([(90, 400), (270, 235), (460, 372), (650, 262), (870, 410)], fill='#859ba0')
            d.polygon([(90, 470), (360, 360), (650, 460), (870, 375), (870, 560), (90, 560)], fill='#9aab83')
            d.polygon([(430, 560), (510, 450), (590, 395), (525, 467), (520, 560)], fill='#d9c9a3')
            for _ in range(600):
                x, y = rng.randint(95, 862), rng.randint(405, 552)
                d.line((x, y, x+rng.randint(5, 17), y-2), fill=(83, 108, 67, rng.randint(20, 90)), width=3)
        else:
            d.line((450, 594, 512, 159), fill='#807b4a', width=5)
            colors = [(197, 125, 63), (161, 163, 81), (196, 161, 65), (159, 110, 74)]
            for i in range(8):
                y = 205+i*45
                x = 512-(y-159)*62/435
                side = -1 if i%2 else 1
                color = colors[(i+seed)%4]
                d.polygon([(x, y+40), (x+side*158, y-58), (x+side*174, y-5), (x+side*82, y+53)], fill=(*color, 145))
                d.line((x, y+40, x+side*158, y-58), fill=(*color, 190), width=2)
    try:
        font = ImageFont.truetype('DejaVuSans.ttf', 17)
    except OSError:
        font = ImageFont.load_default()
    d.text((55, 655), f'STUDY {seed:03d} / V{revision}  -  FICTIONAL DEMO ILLUSTRATION', fill='#817d74', font=font)
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
