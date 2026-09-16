"""Re-sign only the test runner so it can instrument the non-debuggable release APK."""
from pathlib import Path
import os,subprocess,argparse
ROOT=Path(__file__).resolve().parent.parent
p=argparse.ArgumentParser();p.add_argument('--apksigner',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
a=p.parse_args()
config=ROOT/'.signing/signing.properties'
values={}
if config.exists():
    for line in config.read_text(encoding='utf8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key,value=line.split('=',1);values[key.strip()]=value.strip()
mapping={'storeFile':'TADPOLE_STORE_FILE','storePassword':'TADPOLE_STORE_PASSWORD','keyAlias':'TADPOLE_KEY_ALIAS','keyPassword':'TADPOLE_KEY_PASSWORD'}
for key,variable in mapping.items():values[key]=os.environ.get(variable) or values.get(key)
if not all(values.values()) or any(k not in values for k in mapping):raise SystemExit('Release signing configuration is incomplete.')
env=os.environ.copy();env['TADPOLE_STORE_PASSWORD']=values['storePassword'];env['TADPOLE_KEY_PASSWORD']=values['keyPassword']
a.output.parent.mkdir(parents=True,exist_ok=True)
subprocess.run([str(a.apksigner),'sign','--ks',str(ROOT/values['storeFile']),'--ks-key-alias',values['keyAlias'],
    '--ks-pass','env:TADPOLE_STORE_PASSWORD','--key-pass','env:TADPOLE_KEY_PASSWORD','--out',str(a.output),
    str(ROOT/'release-check/build/outputs/apk/debug/release-check-debug.apk')],env=env,check=True)
print('Signed release smoke-test runner; production APK was not modified.')
