from pathlib import Path
import json,numpy as np
from analysis_paths import ROOT,MODEL_DIR,OUTPUT as OUT
OUT.mkdir(exist_ok=True)
model=json.loads((MODEL_DIR/'range_model.json').read_text())
targets=[];golden=[];sweep_targets=[];manifest=[]
for r in model['ranges']:
    data=json.loads((MODEL_DIR/f"{Path(r['file']).stem}_frames.json").read_text())
    frames=data['sweeps'][2];rmanifest=[]
    selected=set(np.linspace(0,len(frames)-1,9).round().astype(int))
    for i,f in enumerate(frames):
        index=len(targets);targets.append(f"{index},{r['name']},{f['frequency']:.9f}")
        rmanifest.append(dict(index=index,**f))
        if i in selected:
            golden.append(','.join([r['name'],str(f['frequency']),*[str(x) for x in f['amplitudes']]]))
    start,end=data['regions'][2];offset=start-.2;duration=end-start+.6
    tt=np.array([f['time'] for f in frames]);ff=np.array([f['frequency'] for f in frames])
    times=offset+np.arange(int(np.ceil(duration/.004)))*.004
    frequencies=np.exp(np.interp(times,tt,np.log(ff)))
    controls=OUT/f"{r['name'].lower()}_controls.csv"
    controls.write_text('\n'.join(f'{t-offset:.6f},{f:.8f},{int(start<=t<end)}' for t,f in zip(times,frequencies))+'\n')
    sweep_targets.append(f"{len(sweep_targets)},{r['name']},{controls.relative_to(ROOT).as_posix()},{offset}")
    manifest.append(dict(name=r['name'],file=r['file'],source_offset=offset,duration=len(times)*.004,frames=rmanifest))
(OUT/'validation_targets.csv').write_text('\n'.join(targets)+'\n')
(OUT/'sweep_targets.csv').write_text('\n'.join(sweep_targets)+'\n')
(OUT/'validation_manifest.json').write_text(json.dumps(manifest),encoding='utf8')
(ROOT/'app/src/test/resources/range_validation.csv').write_text('\n'.join(golden)+'\n')
print(len(targets),'held-out tones;',len(golden),'JVM golden windows; three control sweeps')
