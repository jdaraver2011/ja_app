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
if __name__=='__main__':unittest.main()
