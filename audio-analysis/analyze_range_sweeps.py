#!/usr/bin/env python3
"""Measure three descending sweeps per range; fit on sweeps 1/2, validate on 3.

The phase demodulation and cycle-synchronous FFT below prevent a moving pitch
from being mistaken for lost harmonics. Input/output and rejected windows are
recorded for reproducibility. No UI, recording playback, or Android dependencies.
"""
from pathlib import Path
import argparse, hashlib, json
import numpy as np
from scipy.io import wavfile
from scipy import signal, ndimage
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

CONFIG=[('low','LOW',32,700,384),('mid','MID',100,2000,128),('hi','HIGH',350,6500,32)]
TABLE_SIZE=2048

def read_audio(path):
    sr,y=wavfile.read(path)
    if y.dtype!=np.int16:raise ValueError('Expected PCM16 reference WAV')
    x=y.astype(np.float64)/32768
    channels=1 if x.ndim==1 else x.shape[1]
    if channels>1:x=x.mean(axis=1)
    return sr,x,channels

def pitch_track(x,sr,fmin,fmax):
    size=1536;hop=120
    lo=max(2,int(sr/fmax));hi=min(size//2,int(sr/fmin))
    times=[];frequencies=[];confidence=[];rms=[]
    for start in range(0,len(x)-size,hop):
        frame=x[start:start+size].copy();frame-=frame.mean()
        ac=np.fft.irfft(abs(np.fft.rfft(frame,4096))**2,4096)[:hi+1]
        sums=np.r_[0,np.cumsum(frame*frame)];tau=np.arange(hi+1)
        difference=np.maximum(sums[size-tau]+sums[size]-sums[tau]-2*ac,0)/(size-tau)
        cmnd=np.ones(hi+1)
        cmnd[1:]=difference[1:]*tau[1:]/np.maximum(np.cumsum(difference[1:]),1e-20)
        lag=lo+np.argmin(cmnd[lo:hi])
        for j in range(lo,hi-1):
            if cmnd[j]<.20:
                while j+1<hi and cmnd[j+1]<cmnd[j]:j+=1
                lag=j;break
        delta=0.
        if 0<lag<hi:
            den=cmnd[lag-1]-2*cmnd[lag]+cmnd[lag+1]
            if abs(den)>1e-10:delta=np.clip(.5*(cmnd[lag-1]-cmnd[lag+1])/den,-.5,.5)
        times.append((start+size/2)/sr);frequencies.append(sr/(lag+delta))
        confidence.append(1-cmnd[lag]);rms.append(np.sqrt(np.mean(frame*frame)))
    return tuple(np.asarray(a) for a in [times,frequencies,confidence,rms])

def regions_from_rms(t,rms):
    threshold=max(float(np.percentile(rms,15))*5,.003)
    active=signal.convolve((rms>threshold).astype(float),np.ones(21)/21,mode='same')>.6
    edges=np.diff(np.r_[False,active,False].astype(int))
    regions=[]
    for a,b in zip(np.where(edges==1)[0],np.where(edges==-1)[0]):
        end=t[min(b,len(t)-1)]
        if end-t[a]>1:regions.append((float(t[a]),float(end)))
    if len(regions)!=3:raise RuntimeError(f'Expected 3 complete sweeps, detected {regions}')
    return regions,threshold

def correct_octaves(t,f,confidence,rms,start,end,threshold):
    valid=(t>=start)&(t<=end)&(confidence>.4)&(rms>threshold)
    tt=t[valid];raw=f[valid]
    previous=np.median(raw[tt<tt[0]+.3])
    clean=[];corrections=0
    for value in raw:
        candidates=value*np.array([.25,1/3,.5,1,2,3,4])
        index=np.argmin(abs(np.log(candidates/previous)))
        best=candidates[index]
        if abs(np.log(best/previous))<.12:
            previous=best
            if index!=3:corrections+=1
        clean.append(previous)
    smooth=signal.savgol_filter(ndimage.median_filter(np.log(clean),size=9),17,2)
    return tt,np.exp(smooth),corrections

def extract_frame(x,sr,track_t,track_f,center,max_h,noise):
    f=float(np.interp(center,track_t,track_f))
    duration=float(np.clip(4/f,.016,.080))
    context=max(.18,14/f)
    first=max(0,int((center-context/2)*sr));last=min(len(x),int((center+context/2)*sr))
    y=x[first:last].copy();y-=y.mean()
    t=np.arange(first,last)/sr
    seed=np.interp(t,track_t,track_f)
    phase=np.cumsum(seed)/sr
    use=abs(t-center)<duration/2
    spectrum=abs(np.fft.rfft(y[use]*np.hanning(use.sum()),16384))
    hz=np.fft.rfftfreq(16384,1/sr)
    band=(hz>.7*f)&(hz<min(12000,48*f))
    harmonic=max(1,round(hz[band][np.argmax(spectrum[band])]/f))
    demod=signal.sosfiltfilt(signal.butter(3,.30*f,fs=sr,output='sos'),
        y*np.exp(-2j*np.pi*harmonic*phase))
    residual=np.unwrap(np.angle(demod))
    polynomial=np.polynomial.polynomial.polyfit(t[use]-center,residual[use],3,w=abs(demod[use]))
    corrected=phase+np.polynomial.polynomial.polyval(np.clip(t-center,-duration/2,duration/2),polynomial)/(2*np.pi*harmonic)
    fc=f+polynomial[1]/(2*np.pi*harmonic)
    if fc<=0 or abs(np.log(fc/f))>.06 or np.min(np.diff(corrected))<=0:
        raise ValueError('unreliable instantaneous phase')
    # Upsample before phase warping so ordinary linear interpolation does not
    # impose the original sample grid's strong near-Nyquist rolloff.
    up=signal.resample_poly(y,4,1)
    tu=t[0]+np.arange(len(up))/(sr*4)
    ph=np.interp(tu,t,corrected)
    inside=abs(tu-center)<duration/2
    begin=np.ceil(ph[inside][0]);finish=np.floor(ph[inside][-1]);cycles=int(finish-begin)
    if cycles<2:raise ValueError('fewer than two complete cycles')
    grid=begin+np.arange(cycles*TABLE_SIZE)/TABLE_SIZE
    samples=np.interp(grid,ph,up).reshape(cycles,TABLE_SIZE)
    sample_times=np.interp(grid,ph,tu)
    window=np.maximum(0,1-((sample_times-center)/(duration/2))**2).reshape(cycles,TABLE_SIZE)
    wave=np.sum(samples*window,axis=0)/np.maximum(window.sum(axis=0),1e-10)
    wave-=wave.mean()
    coefficient=np.fft.rfft(wave)*2/TABLE_SIZE
    count=min(max_h,int(20400/fc))
    individual=np.fft.rfft(samples,axis=1)*2/TABLE_SIZE
    amplitude=np.median(abs(individual[:,1:count+1]),axis=0)
    coherence=abs(coefficient[1:count+1])/np.maximum(amplitude,1e-12)
    floor=max(1e-5,3*noise*np.sqrt(2/(duration*sr)))
    amplitude[(amplitude<floor)|(coherence<.30)]=0
    phase_h=np.angle(coefficient[1:count+1])
    phase_h=np.angle(np.exp(1j*(phase_h-np.arange(1,count+1)*phase_h[0])))
    pred=np.interp(np.mod(corrected[use],1)*TABLE_SIZE,np.arange(TABLE_SIZE+1),np.r_[wave,wave[0]])
    snr=10*np.log10(np.sum(pred*pred)/max(np.sum((pred-y[use])**2),1e-20))
    if snr<8:raise ValueError('low periodic reconstruction confidence')
    return dict(time=float(center),frequency=float(fc),seed_frequency=f,snr_db=float(snr),
        cycles=cycles,rms=float(np.sqrt(np.sum(amplitude*amplitude)/2)),
        amplitudes=np.pad(amplitude,(0,max_h-count)).tolist(),
        phases=np.pad(phase_h,(0,max_h-count)).tolist())

def monotone(values):
    """Equal-weight isotonic fit, increasing; only for spatial calibration curves."""
    blocks=[]
    for i,value in enumerate(values):
        blocks.append([float(value),1,i,i+1])
        while len(blocks)>1 and blocks[-2][0]>blocks[-1][0]:
            b=blocks.pop();a=blocks.pop();n=a[1]+b[1]
            blocks.append([(a[0]*a[1]+b[0]*b[1])/n,n,a[2],b[3]])
    result=np.zeros(len(values))
    for mean,_,start,end in blocks:result[start:end]=mean
    return result

def calibration(frames_by_sweep):
    curves=[];measured=[];positions=np.linspace(0,1,17)
    for frames in frames_by_sweep:
        t=np.array([f['time'] for f in frames]);f=np.array([f['frequency'] for f in frames])
        smooth=np.exp(signal.savgol_filter(np.log(f),min(9,len(f)//2*2-1),2))
        near_start=t<t[0]+.7;near_end=t>t[-1]-.45
        # Do not let polynomial smoothing invent an overshooting high endpoint,
        # or average the final descending portion into a raised low endpoint.
        maximum=float(np.median(np.sort(f[near_start])[-3:]))
        minimum=float(np.min(f[near_end]))
        peak_index=int(np.argmax(smooth[near_start]))
        candidates=np.where((t<t[0]+.7)&(f>=maximum*.997))[0]
        begin=t[candidates[-1]] if len(candidates) else t[peak_index]
        candidates=np.where((t>t[0]+(t[-1]-t[0])*.65)&(f<=minimum*1.005))[0]
        finish=t[candidates[0]] if len(candidates) else t[-1]
        if finish-begin<1:raise RuntimeError('Invalid movement bounds')
        logf=np.interp(begin+(1-positions)*(finish-begin),t,np.log(smooth))
        normalized=(logf-np.log(minimum))/(np.log(maximum)-np.log(minimum))
        normalized=np.clip(monotone(normalized),0,1);normalized[0]=0;normalized[-1]=1
        curves.append(normalized)
        measured.append(dict(minimum_hz=minimum,maximum_hz=maximum,movement_start=begin,movement_end=finish))
    low=float(np.median([m['minimum_hz'] for m in measured]))
    # Highest well-supported ends of two takes, avoiding one incomplete squeeze.
    high=float(np.mean(sorted(m['maximum_hz'] for m in measured)[-2:]))
    shape=monotone(np.median(curves,axis=0));shape[0]=0;shape[-1]=1
    curve=np.exp(np.log(low)+shape*(np.log(high)-np.log(low)))
    return dict(positions=positions.tolist(),frequencies=curve.tolist(),top_hz=low,
        middle_hz=float(curve[8]),bottom_hz=high,sweeps=measured,
        curve_note='Time-normalized median of three approximately constant-speed descending sweeps; not a ruler-calibrated spatial measurement.')

def align_phase(amplitude,phase,previous_a,previous_p):
    h=np.arange(1,len(amplitude)+1)
    spectrum=np.zeros(TABLE_SIZE//2+1,dtype=complex)
    spectrum[1:len(amplitude)+1]=amplitude*previous_a*np.exp(1j*(phase-previous_p))
    correlation=np.fft.irfft(spectrum,n=TABLE_SIZE)
    shift=int(np.argmax(correlation))/TABLE_SIZE
    return np.angle(np.exp(1j*(phase+2*np.pi*h*shift)))

def fit_model(frames_by_sweep,cal,max_h):
    # Third sweep is held out from timbre, phase and level fitting.
    first,second=frames_by_sweep[:2]
    grid=np.geomspace(max(min(f['frequency'] for f in first),min(f['frequency'] for f in second)),
        min(max(f['frequency'] for f in first),max(f['frequency'] for f in second)),60)
    def rms_at(frames):
        ordered=sorted(frames,key=lambda x:x['frequency'])
        return np.exp(np.interp(np.log(grid),np.log([f['frequency'] for f in ordered]),np.log([max(f['rms'],1e-9) for f in ordered])))
    second_gain=float(np.exp(np.median(np.log(rms_at(first)/rms_at(second)))))
    train=[]
    for sweep,frames in enumerate([first,second]):
        for frame in frames:
            train.append({**frame,'amplitudes':(np.array(frame['amplitudes'])*(second_gain if sweep==1 else 1)).tolist()})
    low=cal['top_hz'];high=cal['bottom_hz']
    count=int(np.ceil(np.log2(high/low)*24))+1
    frequencies=np.geomspace(low,high,count)
    logs=np.log([f['frequency'] for f in train]);amps=np.array([f['amplitudes'] for f in train])
    anchors=[]
    for f in frequencies:
        distance=abs(logs-np.log(f))
        chosen=np.argsort(distance)[:min(7,len(train))]
        within=chosen[distance[chosen]<.045]
        if len(within)>=3:chosen=within
        amplitude=np.median(amps[chosen],axis=0)
        nearest=min(chosen,key=lambda i:distance[i]+.003/max(train[i]['snr_db'],1))
        phase=np.array(train[nearest]['phases'])
        if anchors:
            phase=align_phase(amplitude,phase,np.array(anchors[-1]['amplitudes']),np.array(anchors[-1]['phases']))
        anchors.append(dict(frequency=float(f),amplitudes=amplitude.tolist(),phases=phase.tolist(),
            support=int(len(chosen)),phase_source_time=train[nearest]['time']))
    return dict(max_harmonics=max_h,training_sweeps=[0,1],validation_sweep=2,second_take_gain=second_gain,anchors=anchors)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--root',type=Path,default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--reuse',action='store_true',help='reuse saved per-frame measurements')
    args=parser.parse_args();root=args.root.resolve();out=root/'audio-analysis/model';out.mkdir(exist_ok=True)
    plots=root/'audio-analysis/output';plots.mkdir(exist_ok=True)
    result=dict(version=1,table_size=TABLE_SIZE,source_condition='Mouth open, fixed volume, three approximately uniform high-to-low sweeps per file',ranges=[])
    for filename,label,fmin,fmax,max_h in CONFIG:
        source=root/'reference/audio'/(filename+'.wav');sr,x,channels=read_audio(source)
        sha=hashlib.sha256(source.read_bytes()).hexdigest()
        cache=out/(filename+'_frames.json')
        if args.reuse and cache.exists():
            data=json.loads(cache.read_text(encoding='utf8'))
            assert data['sha256']==sha,'Reference changed; do not reuse measurements'
            all_frames=data['sweeps'];regions=data['regions'];track=data['track'];rejected=data['rejected']
        else:
            t,f,c,rms=pitch_track(signal.resample_poly(x,1,2),sr//2,fmin,fmax)
            regions,threshold=regions_from_rms(t,rms);noise=float(np.percentile(rms,15))
            all_frames=[];track=[];rejected=[]
            for sweep,(start,end) in enumerate(regions):
                tt,ff,corrections=correct_octaves(t,f,c,rms,start,end,threshold)
                frames=[]
                for when in np.arange(start+.065,end-.065,.055):
                    try:frames.append(extract_frame(x,sr,tt,ff,float(when),max_h,noise))
                    except ValueError as error:rejected.append(dict(sweep=sweep,time=float(when),reason=str(error)))
                if len(frames)<30:raise RuntimeError(f'{label} sweep {sweep}: not enough reliable frames')
                all_frames.append(frames);track.append(dict(time=tt.tolist(),frequency=ff.tolist(),octave_corrections=corrections))
                print(f'{label} sweep {sweep+1}: {len(frames)} windows',flush=True)
            cache.write_text(json.dumps(dict(sha256=sha,sweeps=all_frames,regions=regions,track=track,rejected=rejected)),encoding='utf8')
        cal=calibration(all_frames);model=fit_model(all_frames,cal,max_h)
        result['ranges'].append(dict(name=label,file=source.name,sha256=sha,sample_rate=sr,channels=channels,
            duration=len(x)/sr,calibration=cal,model=model,training_windows=sum(map(len,all_frames[:2])),
            validation_windows=len(all_frames[2]),rejected_windows=len(rejected)))
        fig,axes=plt.subplots(3,1,figsize=(12,8),layout='constrained')
        axes[0].plot(np.arange(0,len(x),48)/sr,x[::48],lw=.45,color='#486d69')
        axes[0].set(title=f'{label} · three descending sweeps',ylabel='Amplitude')
        for i,frames in enumerate(all_frames):
            axes[1].plot([v['time'] for v in frames],[v['frequency'] for v in frames],'.-',ms=2,lw=.7,label=f'Sweep {i+1}')
        axes[1].set(yscale='log',ylabel='Refined F0 / Hz');axes[1].legend()
        hz,st,z=signal.stft(x,sr,nperseg=4096,noverlap=3616)
        axes[2].pcolormesh(st,hz,20*np.log10(abs(z)+1e-8),vmin=-85,vmax=-25,cmap='magma',rasterized=True)
        axes[2].set(yscale='log',ylim=(40,20000),xlabel='Time / s',ylabel='Frequency / Hz')
        fig.savefig(plots/(filename+'_analysis.png'),dpi=140);plt.close(fig)
        print(label,'calibration',*[round(cal[k],2) for k in ['top_hz','middle_hz','bottom_hz']],flush=True)
    # One common gain preserves the measured relative level, both across pitches
    # and across ranges. It does NOT flatten every anchor to the same RMS.
    peak=0.
    phase=np.arange(TABLE_SIZE)/TABLE_SIZE
    for entry in result['ranges']:
        for anchor in entry['model']['anchors']:
            spectrum=np.zeros(TABLE_SIZE//2+1,dtype=complex)
            a=np.array(anchor['amplitudes']);p=np.array(anchor['phases'])
            spectrum[1:len(a)+1]=a*np.exp(1j*p)*TABLE_SIZE/2
            peak=max(peak,float(max(abs(np.fft.irfft(spectrum,n=TABLE_SIZE)))))
    result['output_gain']=min(2.,.85/max(peak,1e-6))
    result['maximum_model_peak']=peak
    (out/'range_model.json').write_text(json.dumps(result,indent=2),encoding='utf8')
    print('Model gain',result['output_gain'],'peak',peak)

if __name__=='__main__':main()
