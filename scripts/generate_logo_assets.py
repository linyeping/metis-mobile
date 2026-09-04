from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "branding" / "metis-logo-source.webp"
MASTER = ROOT / "branding" / "metis-logo-rounded.png"
RES = ROOT / "app" / "src" / "main" / "res"

CROP = (150, 130, 1170, 1150)
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


def main() -> None:
    source = Image.open(SOURCE).convert("RGB").crop(CROP)
    MASTER.parent.mkdir(parents=True, exist_ok=True)
    rounded(source, 1024, 0.22).save(MASTER, optimize=True)

    for density, size in DENSITIES.items():
        target = RES / f"mipmap-{density}"
        target.mkdir(parents=True, exist_ok=True)
        rounded(source, size, 0.22).save(target / "ic_launcher.png", optimize=True)
        rounded(source, size, 0.5).save(target / "ic_launcher_round.png", optimize=True)

    foreground_size = 432
    foreground = Image.new("RGBA", (foreground_size, foreground_size), (0, 0, 0, 0))
    inset = 66
    artwork = rounded(source, foreground_size - inset * 2, 0.22)
    foreground.alpha_composite(artwork, (inset, inset))
    drawable_dir = RES / "drawable-nodpi"
    drawable_dir.mkdir(parents=True, exist_ok=True)
    foreground.save(drawable_dir / "metis_launcher_foreground.png", optimize=True)


if __name__ == "__main__":
    main()
