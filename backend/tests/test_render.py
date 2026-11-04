import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_render import settings


class RenderSettingsTests(unittest.TestCase):
    def test_environment(self):
        env={'RENDER_EXTERNAL_HOSTNAME':'factory-demo.onrender.com','PORT':'10000',
             'TV_CLOUD_TV_KEY':'a'*40,'TV_CLOUD_ADMIN_TOKEN':'b'*40,
             'TV_CLOUD_ADMIN_PASSWORD':'a-long-test-password'}
        self.assertEqual(settings(env)[:2],('factory-demo.onrender.com',10000))
        for key,value in [('RENDER_EXTERNAL_HOSTNAME','demo.evil.com'),
                          ('RENDER_EXTERNAL_HOSTNAME','demo.onrender.com@evil.com'),
                          ('PORT','abc'),('PORT','80'),('TV_CLOUD_TV_KEY','bad'),
                          ('TV_CLOUD_ADMIN_PASSWORD','bad')]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                settings({**env,key:value})


if __name__=='__main__':unittest.main()
