import MidiWriter from 'midi-writer-js';
import { Midi } from '@tonejs/midi';
import fs from 'fs';

export function writeClipsToMidi(clips, outPath) {
  const track = new MidiWriter.Track();
  for (const clip of clips) {
    for (const note of clip.notes ?? []) {
      track.addEvent(new MidiWriter.NoteEvent({
        pitch: Array.isArray(note) ? note : [note],
        duration: '8',
      }));
    }
  }
  const writer = new MidiWriter.Writer([track]);
  fs.writeFileSync(outPath, writer.buildFile());
  return outPath;
}

export function readMidi(path) {
  return new Midi(fs.readFileSync(path));
}
