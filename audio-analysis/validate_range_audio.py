"""Compare actual compiled DSP with the entire held-out third take in each range.

Spectral error removes one scalar gain per window; level-curve error removes
only ONE scalar gain per range. The latter therefore penalizes flattened notes.
Reference recordings and per-window measurements are local analysis inputs.
"""
from pathlib import Path
import json
import numpy as np
from scipy import signal
from scipy.io import wavfile
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

from analysis_paths import ROOT,MODEL_DIR,REFERENCE,OUTPUT as OUT,RENDERED,app_version
MANIFEST=json.loads((OUT/'validation_manifest.json').read_text())
MODEL=json.loads((MODEL_DIR/'range_model.json').read_text())
PREVIEWS=ROOT/'app/build/previews';PREVIEWS.mkdir(parents=True,exist_ok=True)

def measurement(path,f,count):
    x=np.fromfile(path,dtype='<f4').astype(float)
    if not np.all(np.isfinite(x)) or np.max(abs(x))>.941:raise AssertionError('Unsafe PCM')
    window=np.hanning(len(x));size=131072
    spectrum=abs(np.fft.rfft(x*window,size))*2/window.sum()
    amps=np.interp(np.arange(1,count+1)*f,np.fft.rfftfreq(size,1/48000),spectrum,right=0)
    return dict(amplitudes=amps.tolist(),rms=float(np.sqrt(np.mean(x*x))),peak=float(np.max(abs(x))))

def score(frames,measurements):
    errors=[];levels=[];sim=[];weak=[];bands=[[],[],[]]
    for frame,m in zip(frames,measurements):
        a=np.array(frame['amplitudes']);b=np.array(m['amplitudes']);f=frame['frequency']
        h=np.arange(1,len(a)+1)*f;keep=(a>=a.max()*10**(-35/20))&(h<16000)
        weights=a[keep]**2;weights/=weights.sum()
        delta=20*np.log10(np.maximum(b[keep],1e-10)/a[keep]);gain=np.sum(weights*delta)
        errors.append(float(np.sqrt(np.sum(weights*(delta-gain)**2))))
        weak.append(float(np.sqrt(np.mean((delta-gain)**2))))
        levels.append(float(20*np.log10(max(m['rms'],1e-10)/frame['rms'])))
        sim.append(float(np.dot(a,b)/np.linalg.norm(a)/max(np.linalg.norm(b),1e-20)))
        b=b*10**(-gain/20)
        for dest,(lo,hi) in zip(bands,[(40,600),(600,4000),(4000,16000)]):
            band=(h>=lo)&(h<hi)
            aa=np.sum(a[band]**2);bb=np.sum(b[band]**2)
            if aa>np.sum(a*a)*.0001:dest.append(float(10*np.log10(max(bb,1e-20)/aa)))
    levels=np.array(levels);offset=float(np.median(levels));levels-=offset
    return dict(windows=len(frames),weighted_shape_mean_db=float(np.mean(errors)),weighted_shape_p95_db=float(np.percentile(errors,95)),
        partial_shape_mean_db=float(np.mean(weak)),level_curve_rmse_db=float(np.sqrt(np.mean(levels**2))),
        magnitude_cosine_median=float(np.median(sim)),level_offset_db=offset,
        band_abs_error_median_db=[float(np.median(abs(np.array(x)))) if x else None for x in bands]),errors,levels

results=[];all_previews=[];curves=[]
for entry in MANIFEST:
    frames=entry['frames'];name=entry['name'];current=[]
    for frame in frames:
        index=frame['index'];f=frame['frequency'];count=len(frame['amplitudes'])
        current.append(measurement(RENDERED/f'{index:03d}.f32',f,count))
    new,errors,levels=score(frames,current)
    result=dict(range=name,current=new,calibration=next(x['calibration'] for x in MODEL['ranges'] if x['name']==name))
    results.append(result)
    curves.append((name,frames,current,errors,levels))
    print(name,json.dumps(result,ensure_ascii=False))
    # True A/B: whole third sweep, same control trajectory, one RMS gain for the
    # entire rendered sweep. No individual note/anchor normalization.
    synth=np.fromfile(RENDERED/f'{name.lower()}_sweep.f32',dtype='<f4').astype(float)
    sr,raw=wavfile.read(REFERENCE/entry['file']);assert sr==48000
    start=round(entry['source_offset']*sr)
    reference=raw[start:start+len(synth)].astype(float)/32768
    assert len(reference)==len(synth)
    gain=float(np.linalg.norm(reference)/max(np.linalg.norm(synth),1e-10));synth*=gain
    assert max(abs(synth))<1
    silence=np.zeros(int(.45*sr));preview=np.r_[reference,silence,synth,silence]
    wavfile.write(PREVIEWS/f'{name.lower()}_comparison.wav',sr,np.round(np.clip(preview,-1,1)*32767).astype('<i2'))
    all_previews.append(preview)
    result['preview_gain']=gain

wavfile.write(PREVIEWS/'range_comparison.wav',48000,np.round(np.concatenate(all_previews)*32767).astype('<i2'))
report=dict(version=app_version(),reference_condition=MODEL['source_condition'],validation='Third sweep from each file excluded from timbre fitting; calibration uses all three.',
    band_edges_hz=[40,600,4000,16000],ranges=results)
(OUT/'validation_results.json').write_text(json.dumps(report,indent=2),encoding='utf8')
fig,axes=plt.subplots(3,2,figsize=(13,11),layout='constrained')
for row,(name,frames,current,errors,levels) in enumerate(curves):
    f=np.array([x['frequency'] for x in frames]);ref=np.array([x['rms'] for x in frames])
    ax=axes[row,0];ax.plot(f,20*np.log10(ref),label='Recording: held-out sweep 3',lw=1.6)
    actual=np.array([m['rms'] for m in current]);delta=np.median(20*np.log10(actual/ref))
    ax.plot(f,20*np.log10(actual)-delta,label='App '+app_version(),lw=1.2,alpha=.8)
    ax.set(xscale='log',title=f'{name}: relative level vs pitch',xlabel='F0 / Hz',ylabel='RMS / dB, one global gain matched');ax.legend(fontsize=8)
    axes[row,1].plot(f,errors,label='Current weighted harmonic error',color='#9c543f')
    axes[row,1].set(xscale='log',title=f'{name}: held-out timbre',xlabel='F0 / Hz',ylabel='Error / dB');axes[row,1].legend(fontsize=8)
fig.savefig(OUT/'validation_comparison.png',dpi=140);plt.close(fig)
