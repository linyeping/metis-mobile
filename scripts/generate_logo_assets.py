from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding" / "metis-logo-source.png"
MASTER = ROOT / "branding" / "metis-logo-rounded.png"
RES = ROOT / "app" / "src" / "main" / "res"

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}


def rounded(image: Image.Image, size: int, radius_ratio: float) -> Image.Image:
    resized = image.resize((size, size), Image.Resampling.LANCZOS).convert("RGBA")
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1),
        radius=max(1, round(size * radius_ratio)),
        fill=255,
    )
    resized.putalpha(mask)
    return resized


def adaptive(image: Image.Image) -> Image.Image:
    # Android masks the central 72dp of a 108dp layer. Extend edge colours
    # into the overscan area, keeping the entire logo in the visible viewport.
    artwork = image.resize((288, 288), Image.Resampling.LANCZOS)
    result = Image.new("RGBA", (432, 432))
    for y in range(432):
        for x in range(432):
            result.putpixel((x, y), artwork.getpixel((
                min(287, max(0, x - 72)), min(287, max(0, y - 72))
            )))
    return result


def main() -> None:
    original = Image.open(SOURCE).convert("RGBA")
    source = Image.new("RGBA", original.size, original.getpixel((0, original.height // 2)))
    source.alpha_composite(original)
    MASTER.parent.mkdir(parents=True, exist_ok=True)
    rounded(source, 1024, 0.22).save(MASTER, optimize=True)

    for density, size in DENSITIES.items():
        target = RES / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        rounded(source, size, 0.22).save(target / "ic_launcher.png", optimize=True)
        rounded(source, size, 0.5).save(target / "ic_launcher_round.png", optimize=True)

    foreground = adaptive(source)
    drawable_dir = RES / "drawable-nodpi"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    foreground.save(drawable_dir / "metis_launcher_foreground.png", optimize=True)


if __name__ == "__main__":
    main()
