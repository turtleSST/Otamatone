"""Create one compact committed verification summary; raw logs remain generated output."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--sdk',type=Path,required=True);p.add_argument('--build-log',type=Path,required=True)
p.add_argument('--debug-log',type=Path,required=True)
p.add_argument('--include-audio',action='store_true',help='Include optional local reference-recording validation.')
a=p.parse_args()
for log in [a.build_log,a.debug_log]:assert 'BUILD SUCCESSFUL' in log.read_text(encoding='utf-8-sig'),str(log)
def totals(paths):
    result=dict(tests=0,failures=0,errors=0,skipped=0)
    for path in paths:
        suite=ET.parse(path).getroot()
        for key in result:result[key]+=int(suite.get(key,0))
    assert result['tests']>0 and result['failures']==0 and result['errors']==0
    result['passed']=result['tests']-result['skipped']
    return result
tests={kind:totals((ROOT/'app/build/test-results'/kind).glob('TEST-*.xml')) for kind in ['testDebugUnitTest','testReleaseUnitTest']}
tests['android_debug']=totals((ROOT/'app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
metadata=json.loads((ROOT/'app/build/outputs/apk/release/output-metadata.json').read_text())['elements'][0]
apk=ROOT/'app/build/outputs/apk/release'/metadata['outputFile']
sha=lambda path:hashlib.sha256(path.read_bytes()).hexdigest()
smoke=json.loads((ROOT/'verification-output/release-smoke.json').read_text(encoding='utf-8-sig'))
assert smoke['passed'] and smoke['apk_sha256']==sha(apk)
assert 'OK (1 test)' in (ROOT/'verification-output/release-smoke-log.txt').read_text(encoding='utf-8-sig')
signer=a.sdk/'build-tools/35.0.0'/('apksigner.bat' if (a.sdk/'build-tools/35.0.0/apksigner.bat').exists() else 'apksigner')
signature=subprocess.check_output([str(signer),'verify','--verbose','--print-certs',str(apk)]).decode('utf8',errors='replace')
assert 'CN=Android Debug' not in signature
fingerprint=re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',signature).group(1)
pin=ROOT/'docs/signing-certificate.sha256'
if pin.exists():assert pin.read_text().strip()==fingerprint,'Unexpected signing identity change'
else:pin.write_text(fingerprint+'\n',encoding='utf8')
lint=(ROOT/'app/build/reports/lint-results-debug.txt').read_text(encoding='utf8')
assert 'No issues found.' in lint
result=dict(version=metadata['versionName'],version_code=metadata['versionCode'],
    apk_sha256=sha(apk),apk_bytes=apk.stat().st_size,signing_certificate_sha256=fingerprint,
    release_smoke_passed=True,non_debuggable=True,r8_minified=True,resources_shrunk=True,
    tests=tests,lint='No issues found')
if a.include_audio:
    model=json.loads((ROOT/'audio-analysis/model/range_model.json').read_text())
    assert all(sha(ROOT/'reference/audio'/r['file'])==r['sha256'] for r in model['ranges'])
    audio=json.loads((ROOT/'audio-analysis/output/validation_results.json').read_text())
    moving=json.loads((ROOT/'audio-analysis/output/sweep_validation.json').read_text())
    assert audio['version']==metadata['versionName']
    result.update(audio_windows={r['range']:r['current']['windows'] for r in audio['ranges']},
        weighted_harmonic_error_db={r['range']:r['current']['weighted_shape_mean_db'] for r in audio['ranges']},
        moving_audio_windows=sum(r['windows'] for r in moving))
out=ROOT/'docs/verification';out.mkdir(exist_ok=True)
(out/'release.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf8')
doc=f'''# 正式版验证 · {metadata['versionName']}

交付版本 {metadata['versionName']} / versionCode {metadata['versionCode']}，独立正式签名，APK 约 {apk.stat().st_size/1000000:.2f} MB。

## 结果

- Debug / Release 各 {tests['testDebugUnitTest']['passed']} 项 JVM 测试通过。
- Debug Android 功能测试 {tests['android_debug']['passed']} 项通过。
- 独立 Release 冒烟测试通过，运行的是与本摘要 APK SHA-256 一致的正式二进制。
- Release 不可调试，启用 R8 混淆、代码压缩、资源压缩；签名与 16 KiB ZIP 对齐由交付脚本再次核验。
- Lint 无问题。

独立验证工具自带测试依赖，通过正式签名附加到最终 APK，不依赖被测应用的类名和字段。它检查三档高端 / 中点 / 低端的实际音频诊断频率与非零输出峰值，抬手释放为零，并验证进入后台后返回不会继续旧音。该测试使用 Android 15 模拟器；模拟器未启用宿主声音输出，不代表已测量真实手机的扬声器频响或端到端延迟。

详细机器可读摘要见 `verification/release.json`，签名证书摘要见 `signing-certificate.sha256`。完整的录音对比需额外提供本地录音输入；公开仓库提供编译和单元测试所需的音色模型及参考数据。原始日志与临时导出文件不提交。
'''
(ROOT/'docs/verification.md').write_text(doc,encoding='utf8')
print(json.dumps(result,indent=2))
