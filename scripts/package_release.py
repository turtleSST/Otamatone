"""Package a verified, committed release. Private signing material is never copied."""
from pathlib import Path
import argparse,hashlib,json,re,shutil,subprocess,zipfile

ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--sdk',type=Path,required=True);a=p.parse_args()
def command(args):return subprocess.check_output([str(x) for x in args],cwd=ROOT).decode('utf8',errors='replace')
dirty=command(['git','status','--porcelain','--untracked-files=all'])
if dirty.strip():raise SystemExit('Commit the release source and documentation before packaging.')
for name in command(['git','ls-files','-z']).split('\0'):
    path=Path(name)
    if any(part in {'.signing','.tools','build','output','dist'} for part in path.parts) or path.suffix.lower() in {'.p12','.jks','.keystore','.pfx'} or path.name=='signing.properties':
        raise SystemExit('Refusing to archive private/generated path: '+name)
meta=json.loads((ROOT/'app/build/outputs/apk/release/output-metadata.json').read_text())['elements'][0]
version=meta['versionName']
if not re.fullmatch(r'\d+\.\d+\.\d+',version):raise SystemExit('Expected a stable release version.')
apk=ROOT/'app/build/outputs/apk/release'/meta['outputFile']
build_tools=a.sdk/'build-tools/35.0.0'
signer=build_tools/'apksigner.bat' if (build_tools/'apksigner.bat').exists() else build_tools/'apksigner'
aapt=build_tools/'aapt.exe' if (build_tools/'aapt.exe').exists() else build_tools/'aapt'
zipalign=build_tools/'zipalign.exe' if (build_tools/'zipalign.exe').exists() else build_tools/'zipalign'
signature=command([signer,'verify','--verbose','--print-certs',apk])
if 'CN=Android Debug' in signature or 'Verified using v2 scheme (APK Signature Scheme v2): true' not in signature:
    raise SystemExit('A valid non-debug release signature is required.')
fingerprint=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature).group(1)
if fingerprint!=(ROOT/'docs/signing-certificate.sha256').read_text().strip():
    raise SystemExit('APK signing identity differs from the pinned release certificate.')
badging=command([aapt,'dump','badging',apk])
if 'application-debuggable' in badging:raise SystemExit('Refusing to distribute a debuggable APK.')
command([zipalign,'-c','-P','16','4',apk])
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
verification=json.loads((ROOT/'docs/verification/release.json').read_text())
if verification['apk_sha256']!=sha(apk) or not verification['release_smoke_passed']:
    raise SystemExit('This exact APK has not passed the recorded release smoke test.')
out=ROOT/'dist'/version;out.mkdir(parents=True,exist_ok=True)
commit=command(['git','rev-parse','HEAD']).strip()
if (out/'release.json').exists():
    previous=json.loads((out/'release.json').read_text())
    if previous['apk_sha256']!=sha(apk) or previous['git_commit']!=commit:
        raise SystemExit('This release version is already packaged. Increment the version instead of replacing it.')
delivery=out/f'ElectronicTadpole-{version}-release.apk';shutil.copy2(apk,delivery)
archive=out/f'ElectronicTadpole-{version}-source.zip'
subprocess.run(['git','-c','core.autocrlf=false','-c','core.eol=lf','archive','--format=zip',
    '--prefix=ElectronicTadpole/','-o',str(archive),'HEAD'],cwd=ROOT,check=True)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for name in z.namelist():
        path=Path(name)
        assert not any(part in {'.git','.signing','.tools','build','output','dist'} for part in path.parts),name
        assert path.suffix.lower() not in {'.p12','.jks','.keystore','.pfx'},name
        assert path.name!='signing.properties',name
with zipfile.ZipFile(delivery) as z:
    assert z.testzip() is None
    assert not any(name.lower().endswith(('.wav','.m4a','.jpg')) for name in z.namelist())
mapping=ROOT/'app/build/outputs/mapping/release/mapping.txt'
assert mapping.exists(),'R8 mapping is required for future crash diagnosis.'
shutil.copy2(mapping,out/'mapping.txt')
shutil.copy2(ROOT/'CHANGELOG.md',out/'CHANGELOG.md')
shutil.copy2(ROOT/'docs/release.md',out/'INSTALL-AND-SIGNING.md')
(out/'release.json').write_text(json.dumps(dict(version=version,version_code=meta['versionCode'],git_commit=commit,
    package='com.tadpole.instrument',apk_sha256=sha(delivery),signing_certificate_sha256=fingerprint,
    min_sdk=26,target_sdk=35,debuggable=False,minified=True,resources_shrunk=True),indent=2)+'\n',encoding='utf8')
files=[delivery,archive,out/'mapping.txt',out/'CHANGELOG.md',out/'INSTALL-AND-SIGNING.md',out/'release.json']
(out/'SHA256SUMS.txt').write_text('\n'.join(sha(path)+'  '+path.name for path in files)+'\n',encoding='utf8')
print(f'Prepared {version} from commit {commit[:7]}: {delivery.stat().st_size:,} byte APK; {archive.stat().st_size:,} byte source ZIP.')
print('Release APK: '+str(delivery))
