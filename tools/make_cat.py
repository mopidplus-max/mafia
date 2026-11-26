from PIL import Image, ImageFilter
from collections import deque
import os

SRC = "/data/data/cat.narezany.margycode/cache/tmp/opencode/cat-src.jpg"
OUT = "/storage/emulated/0/00axlam/00aamadia/mafia-lan/app/src/main/assets/web"

im = Image.open(SRC).convert("RGB")
w, h = im.size
px = im.load()

WHITE = 238
visited = bytearray(w * h)
queue = deque()


def near_white(x, y):
    r, g, b = px[x, y]
    return r >= WHITE and g >= WHITE and b >= WHITE


for x in range(w):
    for y in (0, h - 1):
        if near_white(x, y) and not visited[y * w + x]:
            visited[y * w + x] = 1
            queue.append((x, y))
for y in range(h):
    for x in (0, w - 1):
        if near_white(x, y) and not visited[y * w + x]:
            visited[y * w + x] = 1
            queue.append((x, y))

alpha = Image.new("L", (w, h), 255)
ap = alpha.load()

while queue:
    x, y = queue.popleft()
    ap[x, y] = 0
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        nx, ny = x + dx, y + dy
        if 0 <= nx < w and 0 <= ny < h and not visited[ny * w + nx]:
            if near_white(nx, ny):
                visited[ny * w + nx] = 1
                queue.append((nx, ny))

soft = alpha.filter(ImageFilter.MinFilter(3))
alpha = Image.composite(soft, alpha, alpha)

rgba = im.convert("RGBA")
rgba.putalpha(alpha)

bbox = alpha.getbbox()
if bbox:
    rgba = rgba.crop(bbox)

pad = 8
side = max(rgba.size) + pad * 2
canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
canvas.paste(rgba, ((side - rgba.width) // 2, (side - rgba.height) // 2))

big = canvas.resize((512, 512), Image.LANCZOS)
big.save(os.path.join(OUT, "cat.png"), optimize=True)
small = canvas.resize((160, 160), Image.LANCZOS)
small.save(os.path.join(OUT, "cat-small.png"), optimize=True)

print("big", big.size, os.path.getsize(os.path.join(OUT, "cat.png")))
print("small", small.size, os.path.getsize(os.path.join(OUT, "cat-small.png")))
print("alpha bbox", alpha.getbbox())
