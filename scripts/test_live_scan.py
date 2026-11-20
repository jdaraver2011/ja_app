import unittest
from scan_live_tv import hls_snapshot,mpd_snapshot,advancing
class LiveScanTests(unittest.TestCase):
    def hls(self,n=1,end=False):return '#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:'+str(n)+'\n#EXTINF:6,\nsegment'+str(n)+'.ts?token=x\n'+('#EXT-X-ENDLIST\n' if end else '')
    def test_short_rolling_window_is_live(self):
        a=hls_snapshot(self.hls());b=hls_snapshot(self.hls(2));a['type']=b['type']='hls';self.assertTrue(advancing(a,b))
    def test_finite_recording_is_excluded(self):self.assertEqual(hls_snapshot(self.hls(end=True))['state'],'ended')
    def test_frozen_manifest_excluded(self):
        a=hls_snapshot(self.hls());a['type']='hls';self.assertFalse(advancing(a,a))
    def test_token_changes_do_not_prove_progress(self):
        self.assertEqual(hls_snapshot(self.hls())['signature'],hls_snapshot(self.hls().replace('token=x','token=y'))['signature'])
    def test_vod_without_end_tag_excluded(self):self.assertEqual(hls_snapshot(self.hls()+'#EXT-X-PLAYLIST-TYPE:VOD\n')['state'],'ended')
    def test_dash_requires_dynamic(self):
        self.assertEqual(mpd_snapshot('<MPD type="static"/>')['state'],'ended')
        self.assertEqual(mpd_snapshot('<MPD type="dynamic" publishTime="x"/>')['state'],'live')

class FreeTvTests(unittest.TestCase):
    def test_us_only_and_name_with_quoted_comma(self):
        from scan_live_tv import free_tv_us
        text='#EXTM3U\n#EXTINF:-1 tvg-country="US" tvg-id="Test.us" tvg-name="News, Local",News\nhttps://a/live.m3u8\n#EXTINF:-1 tvg-country="GB" tvg-id="Other.us",Foreign\nhttps://b/live.m3u8\n'
        out=free_tv_us(text);self.assertEqual(len(out),1);self.assertEqual(out[0]['name'],'News, Local')
    def test_headers_and_url_options(self):
        from scan_live_tv import free_tv_us
        text='#EXTM3U\n#EXTINF:-1 tvg-country="US",Test\n#EXTVLCOPT:http-user-agent=TestAgent\nhttps://a/live.m3u8|Referer=https%3A%2F%2Fa%2F\n'
        out=free_tv_us(text);self.assertEqual(out[0]['userAgent'],'TestAgent');self.assertEqual(out[0]['referrer'],'https://a/')
    def test_duplicate_keeps_canonical_id(self):
        from scan_live_tv import merge_free_tv
        old=[{'id':'News.us@SD','name':'News','logo':'','url':'https://a/live','userAgent':'','referrer':''}]
        extra=[{'externalId':'Different.us','name':'News Alt','logo':'','url':'https://a/live','userAgent':'','referrer':''}]
        out,stats=merge_free_tv(old,extra);self.assertEqual(len(out),1);self.assertEqual(out[0]['id'],'News.us@SD');self.assertIn('free-tv',out[0]['sources']);self.assertEqual(stats['duplicateStreamEntries'],1)
    def test_alternative_preserves_favorite_identity(self):
        from scan_live_tv import merge_free_tv
        old=[{'id':'News.us@SD','name':'News','logo':'','url':'https://a/live','userAgent':'','referrer':''}]
        extra=[{'externalId':'News.us','name':'News','logo':'','url':'https://b/live','userAgent':'','referrer':''}]
        out,stats=merge_free_tv(old,extra);self.assertEqual(len(out),2);self.assertEqual(out[1]['id'],'News.us@SD')

if __name__=='__main__':unittest.main()
