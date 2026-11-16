import importlib.util, json, tempfile, unittest, hashlib, subprocess, os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('updates', ROOT/'scripts/prepare_update_release.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class UpdateReleaseTests(unittest.TestCase):
 def test_invalid_repository_rejected_before_execution(self):
  for value in ['', 'private repo', '../bad', 'owner/repo;echo x', 'owner/repo/other']:
   with self.assertRaises(ValueError):module.prepare(Path('missing.apk'),value,Path('missing-aapt'),Path('unused'))
 def test_metadata_is_derived_from_apk(self):
  from unittest.mock import patch
  with tempfile.TemporaryDirectory() as directory:
   root=Path(directory);apk=root/'test.apk';apk.write_bytes(b'example APK bytes')
   with patch.object(module.subprocess,'check_output',return_value="package: name='com.example.ja' versionCode='20009' versionName='2.0.9'"):
    tag,name=module.prepare(apk,'owner/updates',Path('aapt'),root/'dist')
   data=json.loads((root/'dist/ja-update.json').read_text())
   self.assertEqual(tag,'v2.0.9');self.assertEqual(data['versionCode'],20009)
   self.assertEqual(data['sha256'],hashlib.sha256(apk.read_bytes()).hexdigest())
   self.assertEqual(data['size'],apk.stat().st_size)
   self.assertEqual(data['apkUrl'],'https://github.com/owner/updates/releases/download/v2.0.9/JA-update.apk')
 def test_wrong_package_rejected(self):
  from unittest.mock import patch
  with patch.object(module.subprocess,'check_output',return_value="package: name='other.app' versionCode='1' versionName='1.0'"):
   with self.assertRaises(ValueError):module.prepare(Path('x'), 'owner/updates',Path('aapt'),Path('unused'))
 def test_publish_requires_signing_key(self):
  env=dict(os.environ,JA_KEYSTORE_BASE64='',PUBLISH_UPDATE='true')
  result=subprocess.run(['python3',str(ROOT/'scripts/restore_update_signing.py')],env=env,capture_output=True,text=True)
  self.assertNotEqual(result.returncode,0);self.assertIn('Publishing requires',result.stderr)
if __name__=='__main__':unittest.main()
