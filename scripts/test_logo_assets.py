import unittest
from xml.etree import ElementTree

from PIL import Image, ImageChops

from generate_logo_assets import DENSITIES, RES, SOURCE, adaptive, rounded


class LogoAssetsTest(unittest.TestCase):
    def setUp(self):
        self.original = Image.open(SOURCE).convert("RGBA")
        self.source = Image.new("RGBA", self.original.size,
                                self.original.getpixel((0, self.original.height // 2)))
        self.source.alpha_composite(self.original)

    def assertPixelsEqual(self, actual, expected):
        self.assertEqual(actual.size, expected.size)
        self.assertIsNone(ImageChops.difference(actual, expected).getbbox(alpha_only=False))

    def test_all_legacy_icons_use_canonical_source(self):
        for density, size in DENSITIES.items():
            for filename, radius in [("ic_launcher.png", 0.22), ("ic_launcher_round.png", 0.5)]:
                with self.subTest(density=density, filename=filename):
                    with Image.open(RES / f"mipmap-{density}" / filename) as icon:
                        self.assertPixelsEqual(icon.convert("RGBA"), rounded(self.source, size, radius))

    def test_adaptive_layer_is_full_bleed(self):
        with Image.open(RES / "drawable-nodpi" / "metis_launcher_foreground.png") as layer:
            self.assertEqual(layer.size, (432, 432))
            self.assertEqual(layer.getchannel("A").getextrema(), (255, 255))
            self.assertPixelsEqual(layer, adaptive(self.source))

    def test_visible_viewport_keeps_whole_source(self):
        with Image.open(RES / "drawable-nodpi" / "metis_launcher_foreground.png") as layer:
            self.assertPixelsEqual(layer.crop((72, 72, 360, 360)),
                                   self.source.resize((288, 288), Image.Resampling.LANCZOS))

    def test_both_adaptive_icons_use_scaled_bitmap(self):
        attribute = "{http://schemas.android.com/apk/res/android}"
        for filename in ["ic_launcher.xml", "ic_launcher_round.xml"]:
            root = ElementTree.parse(RES / "mipmap-anydpi-v26" / filename).getroot()
            self.assertEqual(root.find("foreground").get(attribute + "drawable"),
                             "@drawable/ic_launcher_foreground")
        bitmap = ElementTree.parse(RES / "drawable" / "ic_launcher_foreground.xml").getroot()
        self.assertEqual(bitmap.tag, "bitmap")
        self.assertEqual(bitmap.get(attribute + "gravity"), "fill")
        self.assertEqual(bitmap.get(attribute + "src"), "@drawable/metis_launcher_foreground")


if __name__ == "__main__":
    unittest.main()
