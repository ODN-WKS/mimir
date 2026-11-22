//! One way to make a picture safe to send on: redrawn from its pixels as a
//! JPEG, so no location, camera details or hidden comments come along, with
//! the sides at most [`MAX_SIDE`]. The window's chat and a project task's
//! attachments both use it.

use crate::proto::Picture;
use image::{DynamicImage, ImageDecoder, ImageReader};
use std::io::Cursor;

/// The longest side a picture is sent at.
pub const MAX_SIDE: u32 = 1568;

/// Any JPEG, PNG or WebP, cleaned. None for anything else (a GIF, a phone's
/// HEIC, a broken file): nothing is passed on that can't be cleaned.
pub fn clean(bytes: &[u8]) -> Option<Picture> {
    let mut decoder = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?
        .into_decoder()
        .ok()?;
    let mut limits = image::Limits::default();
    limits.max_image_width = Some(16_384);
    limits.max_image_height = Some(16_384);
    limits.max_alloc = Some(512 * 1024 * 1024);
    decoder.set_limits(limits).ok()?;
    // A photo is turned the way it was taken before the details go.
    let turn = decoder.orientation().ok();
    let mut img = DynamicImage::from_decoder(decoder).ok()?;
    if let Some(o) = turn {
        img.apply_orientation(o);
    }
    redraw(img)
}

/// Pixels already in memory, redrawn.
pub fn redraw(img: DynamicImage) -> Option<Picture> {
    let (w, h) = (img.width(), img.height());
    if w == 0 || h == 0 {
        return None;
    }
    let img = if w.max(h) > MAX_SIDE {
        img.resize(MAX_SIDE, MAX_SIDE, image::imageops::FilterType::Lanczos3)
    } else {
        img
    };
    // See-through parts go on white (a JPEG has none).
    let rgb = if img.color().has_alpha() {
        let mut rgba = img.to_rgba8();
        for p in rgba.pixels_mut() {
            let a = p.0[3] as u32;
            for c in &mut p.0[..3] {
                *c = ((*c as u32 * a + 255 * (255 - a)) / 255) as u8;
            }
        }
        DynamicImage::ImageRgba8(rgba).to_rgb8()
    } else {
        img.to_rgb8()
    };
    let mut out = Vec::new();
    image::codecs::jpeg::JpegEncoder::new_with_quality(&mut out, 85)
        .encode_image(&rgb)
        .ok()?;
    Some(Picture {
        media_type: "image/jpeg".into(),
        base64: crate::events::base64(&out),
    })
}

/// Whether bytes look like a picture of any common kind (so it is cleaned
/// or refused, never kept as it came).
pub fn looks_like_picture(bytes: &[u8], name: &str) -> bool {
    let ext = name.rsplit_once('.').map(|(_, e)| e.to_lowercase());
    let brand = bytes
        .get(8..12)
        .filter(|_| bytes.get(4..8) == Some(b"ftyp".as_slice()));
    bytes.starts_with(b"\xFF\xD8\xFF")
        || bytes.starts_with(b"\x89PNG")
        || bytes.starts_with(b"GIF8")
        || (bytes.len() > 12 && &bytes[..4] == b"RIFF" && &bytes[8..12] == b"WEBP")
        || brand.is_some_and(|b| {
            matches!(
                b,
                b"heic"
                    | b"heix"
                    | b"hevc"
                    | b"hevx"
                    | b"heim"
                    | b"heis"
                    | b"mif1"
                    | b"msf1"
                    | b"avif"
                    | b"avis"
            )
        })
        || matches!(
            ext.as_deref(),
            Some(
                "jpg"
                    | "jpeg"
                    | "png"
                    | "webp"
                    | "gif"
                    | "heic"
                    | "heif"
                    | "avif"
                    | "tif"
                    | "tiff"
                    | "bmp"
            )
        )
}
