//! Audio utilities for read-aloud (Speak call) and listening (Listen call).
//!
//! These were previously split across opus.rs and updates.rs; both are
//! removed in B2, but the speech pipeline that the phone uses to read entries
//! aloud is kept here.

use std::path::{Path, PathBuf};

// ---- path helpers ----

/// Where rendered audio files are cached.
pub fn audio_dir() -> PathBuf {
    crate::paths::data_dir().join("updates")
}

/// Where the voice lives (the same one Read aloud downloads).
pub fn voice_root() -> PathBuf {
    crate::voice::root(&crate::paths::data_dir())
}

/// True if `name` is a file name we're willing to serve (no path traversal).
pub fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() < 100
        && name.ends_with(".ogg")
        && name
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
        && !name.starts_with('.')
        && !name.contains("..")
}

/// Renders `text` as speech into `audio/name` using the downloaded voice.
/// Returns the file name on success.
pub fn render(text: &str, audio: &Path, name: &str) -> Result<String, String> {
    let root = voice_root();
    if !crate::voice::ready(&root) {
        return Err("Voice not downloaded on the PC".into());
    }
    if !opus::available() {
        return Err(
            "This PC can't make the audio yet. Install the Opus library (libopus), then try again."
                .into(),
        );
    }
    let Some((samples, rate)) = crate::voice::render_text(&root, text, 1.0)? else {
        return Err("Nothing to read aloud".into());
    };
    let ogg = opus::ogg_opus(&opus::resample(&samples, rate))?;
    std::fs::create_dir_all(audio).map_err(|e| e.to_string())?;
    let tmp = audio.join(format!(".{name}.part"));
    std::fs::write(&tmp, ogg).map_err(|e| e.to_string())?;
    std::fs::rename(&tmp, audio.join(name)).map_err(|e| e.to_string())?;
    Ok(name.to_string())
}

// ---- Ogg Opus encoding (from opus.rs) ----

mod opus {
    use std::ffi::{CString, c_void};

    const RATE: u32 = 24_000;
    const FRAME: usize = (RATE / 50) as usize;
    const BITRATE: i32 = 24_000;
    const OPUS_APPLICATION_VOIP: i32 = 2048;
    const OPUS_SET_BITRATE_REQUEST: i32 = 4002;
    const OPUS_GET_LOOKAHEAD_REQUEST: i32 = 4027;

    type CreateFn = unsafe extern "C" fn(i32, i32, i32, *mut i32) -> *mut c_void;
    type EncodeFn = unsafe extern "C" fn(*mut c_void, *const i16, i32, *mut u8, i32) -> i32;
    type DestroyFn = unsafe extern "C" fn(*mut c_void);
    type CtlFn = unsafe extern "C" fn(*mut c_void, i32, ...) -> i32;

    struct Lib {
        create: CreateFn,
        encode: EncodeFn,
        destroy: DestroyFn,
        ctl: CtlFn,
    }

    fn lib() -> Result<Lib, String> {
        let name = CString::new("libopus.so.0").map_err(|e| e.to_string())?;
        let h = unsafe { libc::dlopen(name.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL) };
        if h.is_null() {
            return Err("This PC has no Opus audio library (libopus).".into());
        }
        Ok(Lib {
            create: crate::voice::sym(h, "opus_encoder_create")?,
            encode: crate::voice::sym(h, "opus_encode")?,
            destroy: crate::voice::sym(h, "opus_encoder_destroy")?,
            ctl: crate::voice::sym(h, "opus_encoder_ctl")?,
        })
    }

    pub fn available() -> bool {
        lib().is_ok()
    }

    pub fn resample(samples: &[f32], rate: u32) -> Vec<f32> {
        if rate == RATE || samples.is_empty() {
            return samples.to_vec();
        }
        let n = ((samples.len() as u64 * RATE as u64) / rate as u64) as usize;
        let step = rate as f64 / RATE as f64;
        (0..n)
            .map(|i| {
                let x = i as f64 * step;
                let j = x as usize;
                let f = (x - j as f64) as f32;
                let a = samples[j.min(samples.len() - 1)];
                let b = samples[(j + 1).min(samples.len() - 1)];
                a + (b - a) * f
            })
            .collect()
    }

    pub fn ogg_opus(samples: &[f32]) -> Result<Vec<u8>, String> {
        let l = lib()?;
        let mut err = 0;
        let enc = unsafe { (l.create)(RATE as i32, 1, OPUS_APPLICATION_VOIP, &mut err) };
        if enc.is_null() || err != 0 {
            return Err(format!(
                "The audio could not be started on this PC. Try again, and restart Mímir if it keeps happening. (Opus {})",
                err
            ));
        }
        let mut lookahead: i32 = 0;
        unsafe {
            (l.ctl)(enc, OPUS_SET_BITRATE_REQUEST, BITRATE);
            (l.ctl)(enc, OPUS_GET_LOOKAHEAD_REQUEST, &mut lookahead as *mut i32);
        }
        let pre_skip = (lookahead.max(0) as u64 * 2) as u16;
        let pcm: Vec<i16> = samples
            .iter()
            .map(|s| (s.clamp(-1.0, 1.0) * 32767.0) as i16)
            .collect();
        let mut packets = vec![];
        let mut buf = vec![0u8; 4000];
        for chunk in pcm.chunks(FRAME) {
            let mut frame = chunk.to_vec();
            frame.resize(FRAME, 0);
            let n = unsafe {
                (l.encode)(
                    enc,
                    frame.as_ptr(),
                    FRAME as i32,
                    buf.as_mut_ptr(),
                    buf.len() as i32,
                )
            };
            if n < 0 {
                unsafe { (l.destroy)(enc) };
                return Err(format!(
                    "The audio could not be made on this PC. Try again, and restart Mímir if it keeps happening. (Opus {n})"
                ));
            }
            packets.push(buf[..n as usize].to_vec());
        }
        unsafe { (l.destroy)(enc) };
        Ok(ogg(&packets, pre_skip, samples.len() as u64))
    }

    fn ogg(packets: &[Vec<u8>], pre_skip: u16, samples: u64) -> Vec<u8> {
        let serial = 0x4353_7570u32;
        let mut head = b"OpusHead".to_vec();
        head.push(1);
        head.push(1);
        head.extend_from_slice(&pre_skip.to_le_bytes());
        head.extend_from_slice(&RATE.to_le_bytes());
        head.extend_from_slice(&0i16.to_le_bytes());
        head.push(0);
        let vendor = b"Mimir";
        let mut tags = b"OpusTags".to_vec();
        tags.extend_from_slice(&(vendor.len() as u32).to_le_bytes());
        tags.extend_from_slice(vendor);
        tags.extend_from_slice(&0u32.to_le_bytes());
        let mut out = vec![];
        let mut seq = 0u32;
        page(&mut out, &[&head], 0x02, 0, serial, &mut seq);
        page(&mut out, &[&tags], 0x00, 0, serial, &mut seq);
        let end = pre_skip as u64 + samples * 2;
        let mut done = 0u64;
        let mut i = 0;
        while i < packets.len() {
            let mut group: Vec<&[u8]> = vec![];
            let mut segs = 0;
            while i < packets.len() {
                let need = packets[i].len() / 255 + 1;
                if segs + need > 255 || group.len() >= 50 {
                    break;
                }
                segs += need;
                group.push(&packets[i]);
                i += 1;
            }
            done += group.len() as u64 * 960;
            let last = i == packets.len();
            let granule = if last { end } else { pre_skip as u64 + done };
            page(
                &mut out,
                &group,
                if last { 0x04 } else { 0 },
                granule,
                serial,
                &mut seq,
            );
        }
        if packets.is_empty() {
            page(&mut out, &[], 0x04, pre_skip as u64, serial, &mut seq);
        }
        out
    }

    fn page(
        out: &mut Vec<u8>,
        packets: &[&[u8]],
        flags: u8,
        granule: u64,
        serial: u32,
        seq: &mut u32,
    ) {
        let start = out.len();
        out.extend_from_slice(b"OggS");
        out.push(0);
        out.push(flags);
        out.extend_from_slice(&granule.to_le_bytes());
        out.extend_from_slice(&serial.to_le_bytes());
        out.extend_from_slice(&seq.to_le_bytes());
        *seq += 1;
        let crc_at = out.len();
        out.extend_from_slice(&[0; 4]);
        let mut lacing = vec![];
        for p in packets {
            let mut n = p.len();
            while n >= 255 {
                lacing.push(255u8);
                n -= 255;
            }
            lacing.push(n as u8);
        }
        out.push(lacing.len() as u8);
        out.extend_from_slice(&lacing);
        for p in packets {
            out.extend_from_slice(p);
        }
        let c = crc32(&out[start..]);
        out[crc_at..crc_at + 4].copy_from_slice(&c.to_le_bytes());
    }

    fn crc32(data: &[u8]) -> u32 {
        let mut c = 0u32;
        for &b in data {
            c ^= (b as u32) << 24;
            for _ in 0..8 {
                c = if c & 0x8000_0000 != 0 {
                    (c << 1) ^ 0x04c1_1db7
                } else {
                    c << 1
                };
            }
        }
        c
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn ogg_crc_matches_a_known_value() {
            assert_eq!(crc32(b"123456789"), 0x89a1_897f);
        }

        #[test]
        fn resampling_keeps_the_length_in_time() {
            let s = vec![0.5f32; 22_050];
            let r = resample(&s, 22_050);
            assert_eq!(r.len(), 24_000);
            assert!((r[100] - 0.5).abs() < 1e-6);
        }

        #[test]
        fn encodes_when_the_library_is_here() {
            if !available() {
                return;
            }
            let tone: Vec<f32> = (0..24_000)
                .map(|i| (i as f32 * 440.0 * std::f32::consts::TAU / 24_000.0).sin() * 0.3)
                .collect();
            let o = ogg_opus(&tone).unwrap();
            assert!(
                o.len() > 1000 && o.len() < 12_000,
                "{} bytes for 1 s",
                o.len()
            );
        }
    }
}
