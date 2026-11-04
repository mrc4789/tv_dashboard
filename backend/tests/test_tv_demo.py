"""The public demo must remain a standalone mock, not expose configuration secrets."""
import unittest
import test_api

class TvDemoTests(unittest.TestCase):
    setUp = test_api.ApiTests.setUp
    tearDown = test_api.ApiTests.tearDown
    start = test_api.ApiTests.start
    stop = test_api.ApiTests.stop
    request = test_api.ApiTests.request

    def test_public_mock_page_and_alias(self):
        for path in ['/tv-demo', '/tv-demo/', '/tv-demo?department=qa-qc']:
            status, body, headers = self.request('GET', path, headers={})
            self.assertEqual(status, 200)
            self.assertIn('text/html', headers['Content-Type'])
            text = body.decode('utf-8')
            self.assertIn('DỮ LIỆU MẪU', text)
            self.assertNotIn('tv-test-key', text)
            self.assertNotIn('admin-test-key', text)
            self.assertNotIn('<script src=', text)
            self.assertEqual(headers['Cache-Control'], 'no-store')

    def test_demo_does_not_open_arbitrary_files(self):
        for path in ['/tv-demo/../data/credentials.json', '/tv-demo/anything']:
            self.assertEqual(self.request('GET', path, headers={})[0], 404)

    def test_demo_does_not_remove_api_authentication(self):
        self.assertEqual(self.request('GET', '/api/v1/departments', headers={})[0], 401)

if __name__ == '__main__': unittest.main()
