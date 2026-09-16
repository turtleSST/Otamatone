"""Re-measure moving audio rendered by SynthEngine, including table transitions."""
from pathlib import Path
import json,numpy as np
from analyze_range_sweeps import extract_frame

from analysis_paths import ROOT,OUTPUT as OUT,RENDERED
manifest=json.loads((OUT/'validation_manifest.json').read_text());results=[]
for r in manifest:
    x=np.fromfile(RENDERED/f"{r['name'].lower()}_sweep.f32",dtype='<f4').astype(float)
    controls=np.loadtxt(OUT/f"{r['name'].lower()}_controls.csv",delimiter=',')
    # Reconstruct the DSP's exact 4-ms pitch smoothing trajectory as phase seed.
    decay=np.exp(-1/(48000*.004));ff=[];previous=controls[0,1]
    for row in controls:
        segment=row[1]+(previous-row[1])*decay**np.arange(1,193)
        ff.extend(segment);previous=segment[-1]
    t=np.arange(len(x))/48000;ff=np.array(ff)
    scores=[];pitch_errors=[];rejections=[]
    for source in r['frames'][::3]:
        center=source['time']-r['source_offset']
        try:measured=extract_frame(x,48000,t,ff,center,len(source['amplitudes']),0)
        except ValueError as e:rejections.append(dict(time=center,reason=str(e)));continue
        a=np.array(source['amplitudes']);b=np.array(measured['amplitudes']);h=np.arange(1,len(a)+1)*source['frequency']
        use=(a>a.max()*10**(-35/20))&(h<16000)
        weight=a[use]**2;weight/=weight.sum()
        delta=20*np.log10(np.maximum(b[use],1e-10)/a[use]);delta-=sum(weight*delta)
        scores.append(float(np.sqrt(sum(weight*delta*delta))))
        pitch_errors.append(float(1200*np.log2(measured['frequency']/source['frequency'])))
    results.append(dict(range=r['name'],windows=len(scores),rejections=rejections,
        spectral_mean_db=float(np.mean(scores)),spectral_p95_db=float(np.percentile(scores,95)),
        pitch_cents_median=float(np.median(pitch_errors)),pitch_abs_cents_p95=float(np.percentile(abs(np.array(pitch_errors)),95))))
    print(results[-1],flush=True)
    assert len(scores)>=len(r['frames'][::3])*.9
    assert np.mean(scores)<3.5
(OUT/'sweep_validation.json').write_text(json.dumps(results,indent=2),encoding='utf8')
