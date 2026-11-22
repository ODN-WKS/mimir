//! Pictures in the window's chat: shrinking what the owner attaches,
//! pasting, the thumbnails in the box and in sent messages.

use crate::app::Action;
use eframe::egui;
use image::{DynamicImage, ImageDecoder, ImageReader};
use mimir_core::proto::Picture;
use std::io::Cursor;
use std::path::{Path, PathBuf};

/// At most this many pictures a message.
pub const MAX_PICTURES: usize = 4;
/// The longest side a picture is sent at.
pub const MAX_SIDE: u32 = 1568;
/// A file bigger than this is not read.
const MAX_FILE: u64 = 40 * 1024 * 1024;

/// Said when the answerer can't look at pictures.
pub const CANT_SEE: &str =
    "None of your PC's models can look at pictures right now. Send words only.";
/// Said when a file can't be read as a picture.
pub const NOT_A_PICTURE: &str = "That isn't a picture Mímir can read. Try a photo or a screenshot.";
/// Said when there are already four.
pub const TOO_MANY: &str = "Four pictures at most in one message.";

/// A picture waiting in the message box.
#[derive(Clone)]
pub struct Pending {
    pub id: u64,
    pub pic: Picture,
}

/// Turns any picture (JPEG, PNG, WebP) into what is sent: the sides at
/// most [`MAX_SIDE`], redrawn from its pixels as a JPEG (quality 85), so
/// no location or camera details come along.
pub fn shrink(bytes: &[u8]) -> Option<Picture> {
    let mut decoder = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .ok()?
        .into_decoder()
        .ok()?;
    // A photo is turned the way it was taken before the details go.
    let turn = decoder.orientation().ok();
    let mut img = DynamicImage::from_decoder(decoder).ok()?;
    if let Some(o) = turn {
        img.apply_orientation(o);
    }
    redraw(img)
}

/// The same for pixels already in memory (a paste).
pub fn shrink_pixels(img: &egui::ColorImage) -> Option<Picture> {
    let bytes: Vec<u8> = img
        .pixels
        .iter()
        .flat_map(|c| c.to_srgba_unmultiplied())
        .collect();
    let rgba = image::RgbaImage::from_raw(img.size[0] as u32, img.size[1] as u32, bytes)?;
    redraw(DynamicImage::ImageRgba8(rgba))
}

/// A picture file, shrunk. None for anything else.
pub fn shrink_file(path: &Path) -> Option<Picture> {
    if std::fs::metadata(path).ok()?.len() > MAX_FILE {
        return None;
    }
    shrink(&std::fs::read(path).ok()?)
}

fn redraw(img: DynamicImage) -> Option<Picture> {
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
        base64: mimir_core::events::base64(&out),
    })
}

/// The bytes of a picture as kept.
pub fn bytes_of(p: &Picture) -> Option<Vec<u8>> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(p.base64.as_bytes())
        .ok()
}

/// A picture as pixels for a thumbnail or a copy (the long side at most `side`).
pub fn pixels_of(p: &Picture, side: u32) -> Option<egui::ColorImage> {
    let img = image::load_from_memory(&bytes_of(p)?).ok()?;
    let img = if img.width().max(img.height()) > side {
        img.resize(side, side, image::imageops::FilterType::Triangle)
    } else {
        img
    };
    let rgba = img.to_rgba8();
    Some(egui::ColorImage::from_rgba_unmultiplied(
        [rgba.width() as usize, rgba.height() as usize],
        rgba.as_raw(),
    ))
}

/// A thumbnail, made once per `key`.
pub fn thumbnail(
    ctx: &egui::Context,
    key: &str,
    p: &Picture,
    side: u32,
) -> Option<egui::TextureHandle> {
    let id = egui::Id::unique(("chat-pic", key));
    if let Some(t) = ctx.data(|d| d.get_temp::<egui::TextureHandle>(id)) {
        return Some(t);
    }
    let t = ctx.load_texture(
        format!("chat-pic-{key}"),
        pixels_of(p, side)?,
        egui::TextureOptions::LINEAR,
    );
    ctx.data_mut(|d| d.insert_temp(id, t.clone()));
    Some(t)
}

/// Where Open puts a picture for the desktop's viewer (cleared at the next start).
fn opened_dir() -> PathBuf {
    mimir_core::paths::data_dir().join("opened-pictures")
}

/// Writes a picture to a place the desktop's viewer can open.
pub fn write_for_open(p: &Picture, name: &str) -> Option<PathBuf> {
    let dir = opened_dir();
    std::fs::create_dir_all(&dir).ok()?;
    let path = dir.join(name);
    std::fs::write(&path, bytes_of(p)?).ok()?;
    Some(path)
}

/// Removes what Open left behind earlier.
pub fn clear_opened() {
    let _ = std::fs::remove_dir_all(opened_dir());
}

/// Asks where to save a picture (KDE's or GTK's dialog); the answer comes
/// on the channel.
pub fn pick_save(name: &str) -> std::sync::mpsc::Receiver<Option<PathBuf>> {
    let (tx, rx) = std::sync::mpsc::channel();
    let name = name.to_string();
    std::thread::spawn(move || {
        let start = crate::places::home().join(&name).display().to_string();
        let out = mimir_core::flatpak::host_cmd("kdialog")
            .args(["--getsavefilename", &start, "--title", "Save the picture"])
            .output()
            .or_else(|_| {
                mimir_core::flatpak::host_cmd("zenity")
                    .args([
                        "--file-selection",
                        "--save",
                        "--confirm-overwrite",
                        &format!("--filename={start}"),
                        "--title=Save the picture",
                    ])
                    .output()
            });
        let path = out
            .ok()
            .filter(|o| o.status.success())
            .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
            .filter(|s| !s.is_empty())
            .map(PathBuf::from);
        let _ = tx.send(path);
    });
    rx
}

/// The width a thumbnail takes at `height` (its own shape, within limits).
fn shown_size(tex: &egui::TextureHandle, height: f32, max_w: f32) -> egui::Vec2 {
    let [w, h] = tex.size();
    let aspect = w as f32 / (h as f32).max(1.0);
    let width = (height * aspect).clamp(height * 0.5, max_w);
    egui::vec2(width, height)
}

fn picture_image(tex: &egui::TextureHandle, size: egui::Vec2) -> egui::Image<'static> {
    egui::Image::new(egui::load::SizedTexture::new(tex.id(), size))
        .fit_to_exact_size(size)
        .corner_radius(8.0)
}

/// The pictures waiting in the message box, each with a small ✕, and a
/// spinner for those still being shrunk.
pub fn box_thumbnails(
    ui: &mut egui::Ui,
    pics: &[Pending],
    shrinking: usize,
    actions: &mut Vec<Action>,
) {
    for p in pics {
        let Some(tex) = thumbnail(ui.ctx(), &format!("box{}", p.id), &p.pic, 160) else {
            continue;
        };
        let size = shown_size(&tex, 44.0, 80.0);
        let resp = ui.add(picture_image(&tex, size).sense(egui::Sense::hover()));
        resp.widget_info(|| {
            egui::WidgetInfo::labeled(egui::accesskit::Role::Image, true, "Attached picture")
        });
        let x_rect = egui::Rect::from_min_size(
            resp.rect.right_top() + egui::vec2(-16.0, 1.0),
            egui::vec2(15.0, 15.0),
        );
        let x = ui.interact(
            x_rect,
            egui::Id::unique(("drop-pic", p.id)),
            egui::Sense::click(),
        );
        x.widget_info(|| {
            egui::WidgetInfo::labeled(
                egui::accesskit::Role::Button,
                true,
                crate::labels::REMOVE_PICTURE,
            )
        });
        let fill = if x.hovered() {
            egui::Color32::from_black_alpha(230)
        } else {
            egui::Color32::from_black_alpha(160)
        };
        ui.painter().circle_filled(x_rect.center(), 7.5, fill);
        ui.painter().text(
            x_rect.center(),
            egui::Align2::CENTER_CENTER,
            "✕",
            egui::FontId::proportional(9.5),
            egui::Color32::WHITE,
        );
        if x.on_hover_cursor(egui::CursorIcon::PointingHand)
            .on_hover_text(crate::labels::REMOVE_PICTURE)
            .clicked()
        {
            actions.push(Action::DropChatPicture(p.id));
        }
    }
    for _ in 0..shrinking {
        ui.spinner();
    }
}

/// The pictures of a sent message, above its bubble, at the right. A click
/// opens one; the right-click menu copies, saves or opens it.
pub fn sent_pictures(ui: &mut egui::Ui, turn: &str, pics: &[Picture], actions: &mut Vec<Action>) {
    if pics.is_empty() {
        return;
    }
    ui.with_layout(egui::Layout::right_to_left(egui::Align::Min), |ui| {
        for (i, p) in pics.iter().enumerate().rev() {
            let Some(tex) = thumbnail(ui.ctx(), &format!("{turn}:{i}"), p, 360) else {
                continue;
            };
            let size = shown_size(&tex, 140.0, 220.0);
            let name = format!("picture-{}.jpg", i + 1);
            let resp = ui
                .add(picture_image(&tex, size).sense(egui::Sense::click()))
                .on_hover_cursor(egui::CursorIcon::PointingHand)
                .on_hover_text("Click to open. Right-click for more.");
            resp.widget_info(|| {
                egui::WidgetInfo::labeled(egui::accesskit::Role::Image, true, "Sent picture")
            });
            if resp.clicked() {
                actions.push(Action::PictureOpen(p.clone(), name.clone()));
            }
            resp.context_menu(|ui| {
                if ui.button("Copy").clicked() {
                    actions.push(Action::PictureCopy(p.clone()));
                    ui.close();
                }
                if ui.button("Save as…").clicked() {
                    actions.push(Action::PictureSave(p.clone(), name.clone()));
                    ui.close();
                }
                if ui.button("Open").clicked() {
                    actions.push(Action::PictureOpen(p.clone(), name.clone()));
                    ui.close();
                }
            });
        }
    });
    ui.add_space(4.0);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(w: u32, h: u32, alpha: u8) -> Vec<u8> {
        let img = image::RgbaImage::from_fn(w, h, |x, y| {
            image::Rgba([(x % 255) as u8, (y % 255) as u8, 90, alpha])
        });
        let mut out = Vec::new();
        image::DynamicImage::ImageRgba8(img)
            .write_to(&mut Cursor::new(&mut out), image::ImageFormat::Png)
            .unwrap();
        out
    }

    fn size_of(p: &Picture) -> (u32, u32) {
        let img = image::load_from_memory(&bytes_of(p).unwrap()).unwrap();
        (img.width(), img.height())
    }

    #[test]
    fn a_big_picture_is_shrunk_to_1568_on_its_long_side_and_becomes_a_jpeg() {
        let p = shrink(&png(3136, 1568, 255)).unwrap();
        assert_eq!(p.media_type, "image/jpeg");
        assert_eq!(size_of(&p), (1568, 784));
        let bytes = bytes_of(&p).unwrap();
        assert_eq!(&bytes[..2], &[0xFF, 0xD8]);
    }

    #[test]
    fn a_small_picture_keeps_its_size() {
        let p = shrink(&png(300, 200, 255)).unwrap();
        assert_eq!(size_of(&p), (300, 200));
    }

    #[test]
    fn nothing_but_pixels_is_kept_no_details_ride_along() {
        // A JPEG with a made-up details block (an APP1 "Exif" segment holding a place).
        let plain = shrink(&png(64, 64, 255)).unwrap();
        let mut jpeg = bytes_of(&plain).unwrap();
        let note = b"Exif\0\0GPSLatitude=12.34";
        let mut seg = vec![0xFF, 0xE1];
        seg.extend_from_slice(&((note.len() + 2) as u16).to_be_bytes());
        seg.extend_from_slice(note);
        jpeg.splice(2..2, seg);
        assert!(jpeg.windows(11).any(|w| w == b"GPSLatitude"));
        let out = bytes_of(&shrink(&jpeg).unwrap()).unwrap();
        assert!(!out.windows(11).any(|w| w == b"GPSLatitude"));
        assert!(!out.windows(4).any(|w| w == b"Exif"));
    }

    #[test]
    fn see_through_parts_go_on_white() {
        let p = shrink(&png(8, 8, 0)).unwrap();
        let img = image::load_from_memory(&bytes_of(&p).unwrap())
            .unwrap()
            .to_rgb8();
        let px = img.get_pixel(4, 4).0;
        assert!(px.iter().all(|c| *c > 240), "{px:?}");
    }

    #[test]
    fn webp_is_read() {
        // A tiny lossless WebP written by the encoder.
        let img = image::RgbImage::from_fn(10, 10, |x, _| image::Rgb([x as u8 * 20, 0, 0]));
        let mut out = Vec::new();
        image::DynamicImage::ImageRgb8(img)
            .write_to(&mut Cursor::new(&mut out), image::ImageFormat::WebP)
            .unwrap();
        assert_eq!(size_of(&shrink(&out).unwrap()), (10, 10));
    }

    #[test]
    fn other_files_are_not_pictures() {
        assert!(shrink(b"hello there, this is text").is_none());
        assert!(shrink(&[]).is_none());
    }

    #[test]
    fn a_pasted_picture_is_shrunk_the_same_way() {
        let px = vec![egui::Color32::from_rgb(10, 200, 30); 2000 * 100];
        let img = egui::ColorImage {
            size: [2000, 100],
            source_size: egui::vec2(2000.0, 100.0),
            pixels: px,
        };
        let p = shrink_pixels(&img).unwrap();
        assert_eq!(size_of(&p), (1568, 78));
    }
}
