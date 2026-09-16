"""Create the project's first private release key; never overwrite an existing key.

Passwords are random, kept in an ignored private directory, and passed to
keytool by environment variables. No password appears in command arguments/logs.
"""
from pathlib import Path
import os,secrets,shutil,subprocess

ROOT=Path(__file__).resolve().parent.parent
PRIVATE=ROOT/'.signing';CONFIG=PRIVATE/'signing.properties';KEY=PRIVATE/'release.p12'
if CONFIG.exists() or KEY.exists():
    raise SystemExit('Signing material already exists; reuse it instead of generating a new app identity.')
if (ROOT/'docs/signing-certificate.sha256').exists():
    raise SystemExit('This app already has a release signing identity. Restore its private signing backup instead of creating a different key.')
java_home=os.environ.get('JAVA_HOME')
keytool=str(Path(java_home)/'bin'/('keytool.exe' if os.name=='nt' else 'keytool')) if java_home else shutil.which('keytool')
if not keytool:raise SystemExit('Set JAVA_HOME to a compatible JDK first.')
PRIVATE.mkdir(mode=0o700,exist_ok=True)
if os.name=='nt':
    owner=os.environ.get('USERDOMAIN','')+'\\'+os.environ['USERNAME']
    subprocess.run(['icacls',str(PRIVATE),'/inheritance:r','/grant:r',owner+':(OI)(CI)F',
        '*S-1-5-18:(OI)(CI)F','*S-1-5-32-544:(OI)(CI)F'],check=True,capture_output=True)
password=secrets.token_urlsafe(36)
env=os.environ.copy();env['TADPOLE_STORE_PASSWORD']=password;env['TADPOLE_KEY_PASSWORD']=password
result=subprocess.run([keytool,'-genkeypair','-noprompt','-storetype','PKCS12','-keystore',str(KEY),
    '-alias','electronic-tadpole','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA',
    '-validity','10950','-dname','CN=Electronic Tadpole',
    '-storepass:env','TADPOLE_STORE_PASSWORD','-keypass:env','TADPOLE_KEY_PASSWORD'],
    env=env,capture_output=True)
if result.returncode:raise SystemExit('keytool could not create the release key: '+result.stderr.decode(errors='replace'))
CONFIG.write_text('storeFile=.signing/release.p12\nstorePassword='+password+
    '\nkeyAlias=electronic-tadpole\nkeyPassword='+password+'\n',encoding='utf8')
if os.name!='nt':KEY.chmod(0o600);CONFIG.chmod(0o600)
(PRIVATE/'README.txt').write_text('Private signing material for Electronic Tadpole.\n'
    'Back up this entire directory securely. It is needed to sign future updates.\n'
    'Do not publish it, commit it to Git, or distribute it with the APK/source archive.\n',encoding='utf8')
print('Created private release signing material in .signing/ (excluded from Git and delivery archives).')
