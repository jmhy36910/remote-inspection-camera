import unittest

from remote_camera_control import Controller


class FitDimensionsTest(unittest.TestCase):
    def test_landscape_fits_square_without_stretch(self):
        self.assertEqual((760, 428), Controller._fit_dimensions(1280, 720, 760, 760))

    def test_portrait_fits_square_without_stretch(self):
        self.assertEqual((428, 760), Controller._fit_dimensions(720, 1280, 760, 760))

    def test_wide_canvas_uses_height_limit(self):
        self.assertEqual((711, 400), Controller._fit_dimensions(1920, 1080, 1000, 400))


if __name__ == "__main__":
    unittest.main()
