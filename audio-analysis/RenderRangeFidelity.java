// Execute the actual app DSP for offline reference validation.
import com.tadpole.instrument.audio.*;
import com.tadpole.instrument.model.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

public class RenderRangeFidelity {
    public static void main(String[] args) throws Exception {
        Path output=Path.of(args[1]); Files.createDirectories(output);
        long started=System.nanoTime(); WavetableBank bank=new WavetableBank();
        System.out.printf(Locale.US,"Bank construction %.1f ms%n",(System.nanoTime()-started)/1e6);
        for(String line:Files.readAllLines(Path.of(args[0]))) {
            if(line.isBlank() || line.startsWith("#")) continue;
            String[] parts=line.split(","); int id=Integer.parseInt(parts[0]);
            AudioParameters p=new AudioParameters();
            p.setSettings(p.getSettings().withTone(0f,0f).withRange(PitchRange.valueOf(parts[1])));
            p.setEnabled(true);p.setGate(true);p.setTargetMouthOpen(1f);
            SynthEngine synth=new SynthEngine(48000,bank);
            float[] block=new float[192];
            if(parts.length==3) {
                p.setTargetFrequency(Float.parseFloat(parts[2]));
                for(int i=0;i<100;i++) synth.render(block,192,p,false);
                ByteBuffer pcm=ByteBuffer.allocate(12096*4).order(ByteOrder.LITTLE_ENDIAN);
                for(int i=0;i<63;i++) {synth.render(block,192,p,false);for(float v:block) pcm.putFloat(v);}
                Files.write(output.resolve(String.format(Locale.US,"%03d.f32",id)),pcm.array());
            } else {
                // Four columns: id, range, control CSV, source offset. Controls are
                // sampled every 4 ms; warmup starts with gate closed, preserving attack.
                List<String> controls=Files.readAllLines(Path.of(parts[2]));
                p.setGate(false);p.setTargetFrequency(Float.parseFloat(controls.get(0).split(",")[1]));
                for(int i=0;i<100;i++) synth.render(block,192,p,false);
                ByteBuffer pcm=ByteBuffer.allocate(controls.size()*192*4).order(ByteOrder.LITTLE_ENDIAN);
                for(String row:controls) {
                    String[] control=row.split(",");
                    p.setTargetFrequency(Float.parseFloat(control[1]));p.setGate(control[2].equals("1"));
                    synth.render(block,192,p,false);for(float v:block) pcm.putFloat(v);
                }
                Files.write(output.resolve(parts[1].toLowerCase(Locale.US)+"_sweep.f32"),pcm.array());
            }
        }
        System.out.printf(Locale.US,"Rendered app DSP in %.2f seconds%n",(System.nanoTime()-started)/1e9);
    }
}
