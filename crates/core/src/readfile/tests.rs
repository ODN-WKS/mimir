//! Reading files sent with a chat message: real small sample files, made
//! here (no personal data), through the same code a phone's files go through.

use super::*;
use lopdf::content::{Content, Operation};
use lopdf::{Document, Object, Stream, dictionary};
use std::io::Write as _;

// ------------------------------------------------------------- samples

/// A zip with the given members; `deflate` packs them the usual way.
fn zip(members: &[(&str, &[u8])], deflate: bool) -> Vec<u8> {
    let mut out = vec![];
    let mut central = vec![];
    for (name, data) in members {
        let mut crc = flate2::Crc::new();
        crc.update(data);
        let packed: Vec<u8> = if deflate {
            let mut e = flate2::write::DeflateEncoder::new(vec![], flate2::Compression::default());
            e.write_all(data).unwrap();
            e.finish().unwrap()
        } else {
            data.to_vec()
        };
        let method: u16 = if deflate { 8 } else { 0 };
        let at = out.len() as u32;
        out.extend(b"PK\x03\x04");
        for v in [20u16, 0, method, 0, 0] {
            out.extend(v.to_le_bytes());
        }
        out.extend(crc.sum().to_le_bytes());
        out.extend((packed.len() as u32).to_le_bytes());
        out.extend((data.len() as u32).to_le_bytes());
        out.extend((name.len() as u16).to_le_bytes());
        out.extend(0u16.to_le_bytes());
        out.extend(name.as_bytes());
        out.extend(&packed);
        central.extend(b"PK\x01\x02");
        for v in [20u16, 20, 0, method, 0, 0] {
            central.extend(v.to_le_bytes());
        }
        central.extend(crc.sum().to_le_bytes());
        central.extend((packed.len() as u32).to_le_bytes());
        central.extend((data.len() as u32).to_le_bytes());
        central.extend((name.len() as u16).to_le_bytes());
        for v in [0u16, 0, 0, 0] {
            central.extend(v.to_le_bytes());
        }
        central.extend(0u32.to_le_bytes());
        central.extend(at.to_le_bytes());
        central.extend(name.as_bytes());
    }
    let start = out.len() as u32;
    out.extend(&central);
    out.extend(b"PK\x05\x06");
    for v in [0u16, 0, members.len() as u16, members.len() as u16] {
        out.extend(v.to_le_bytes());
    }
    out.extend((central.len() as u32).to_le_bytes());
    out.extend(start.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    out
}

fn sent(name: &str, bytes: &[u8]) -> SentFile {
    SentFile {
        name: name.into(),
        media_type: String::new(),
        base64: base64::engine::general_purpose::STANDARD.encode(bytes),
    }
}

/// A PDF with one line of text a page; `None` is a page with no text but a
/// picture (a scan).
fn pdf(pages: &[Option<&str>]) -> Vec<u8> {
    let mut doc = Document::with_version("1.5");
    let pages_id = doc.new_object_id();
    let font = doc
        .add_object(dictionary! {"Type" => "Font", "Subtype" => "Type1", "BaseFont" => "Courier"});
    let res = doc.add_object(dictionary! {"Font" => dictionary! {"F1" => font}});
    let mut kids = vec![];
    for p in pages {
        let (ops, resources) = match p {
            Some(t) => (
                vec![
                    Operation::new("BT", vec![]),
                    Operation::new("Tf", vec!["F1".into(), 12.into()]),
                    Operation::new("Td", vec![50.into(), 700.into()]),
                    Operation::new("Tj", vec![Object::string_literal(*t)]),
                    Operation::new("ET", vec![]),
                ],
                Object::Reference(res),
            ),
            None => {
                let img = doc.add_object(Stream::new(
                    dictionary! {"Type" => "XObject", "Subtype" => "Image", "Width" => 8, "Height" => 8,
                        "ColorSpace" => "DeviceRGB", "BitsPerComponent" => 8, "Filter" => "DCTDecode"},
                    b"\xFF\xD8\xFF\xE0 pretend jpeg of a page \xFF\xD9".to_vec(),
                ));
                (
                    vec![
                        Operation::new("q", vec![]),
                        Operation::new("Do", vec!["Im1".into()]),
                        Operation::new("Q", vec![]),
                    ],
                    Object::Dictionary(dictionary! {"XObject" => dictionary! {"Im1" => img}}),
                )
            }
        };
        let content = doc.add_object(Stream::new(
            dictionary! {},
            Content { operations: ops }.encode().unwrap(),
        ));
        let page = doc.add_object(dictionary! {"Type" => "Page", "Parent" => pages_id,
        "Contents" => content, "Resources" => resources});
        kids.push(Object::Reference(page));
    }
    let count = kids.len() as i64;
    doc.objects.insert(
        pages_id,
        Object::Dictionary(
            dictionary! {"Type" => "Pages", "Kids" => kids, "Count" => count,
            "MediaBox" => vec![0.into(), 0.into(), 595.into(), 842.into()]},
        ),
    );
    let cat = doc.add_object(dictionary! {"Type" => "Catalog", "Pages" => pages_id});
    doc.trailer.set("Root", cat);
    let mut out = vec![];
    doc.save_to(&mut out).unwrap();
    out
}

fn docx() -> Vec<u8> {
    zip(
        &[
            ("[Content_Types].xml", b"<Types/>"),
            (
                "word/document.xml",
                br#"<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:r><w:t>Garden planner</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">Plant the beans </w:t></w:r><w:r><w:tab/><w:t>in May.</w:t></w:r></w:p>
<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Row A</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
</w:body></w:document>"#,
            ),
        ],
        true,
    )
}

fn xlsx() -> Vec<u8> {
    zip(
        &[
            (
                "xl/workbook.xml",
                br#"<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheets><sheet name="Seeds" sheetId="1"/><sheet name="Costs" sheetId="2"/></sheets></workbook>"#,
            ),
            (
                "xl/sharedStrings.xml",
                br#"<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><si><t>Beans</t></si><si><t>Peas</t></si></sst>"#,
            ),
            (
                "xl/worksheets/sheet1.xml",
                br#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="C1"><v>12</v></c></row>
<row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>7</v></c></row>
</sheetData></worksheet>"#,
            ),
            (
                "xl/worksheets/sheet2.xml",
                br#"<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Total</t></is></c><c r="B1"><v>40</v></c></row></sheetData></worksheet>"#,
            ),
        ],
        true,
    )
}

fn read_all(f: &SentFile, can_see: bool) -> Fitted {
    let e = extract(f, can_see);
    fit(&[e], FREE_CHARS).remove(0)
}

// --------------------------------------------------------------- tests

#[test]
fn a_pdf_with_text_is_read_page_by_page_and_says_all_of_it() {
    let f = sent(
        "Notes.pdf",
        &pdf(&[
            Some("Page one beans"),
            Some("Page two peas"),
            Some("Page three corn"),
        ]),
    );
    let r = read_all(&f, false);
    assert_eq!(r.note, "Read all of it");
    for w in ["Page one beans", "Page two peas", "Page three corn"] {
        assert!(r.text.contains(w), "{w} missing from {:?}", r.text);
    }
    let line = r.for_model();
    assert!(
        line.starts_with("File \"Notes.pdf\" (read all of it):"),
        "{line}"
    );
}

#[test]
fn a_long_pdf_says_which_pages_were_read() {
    let words: Vec<String> = (1..=10)
        .map(|i| format!("Page {i}: {}", "word ".repeat(60)))
        .collect();
    let pages: Vec<Option<&str>> = words.iter().map(|w| Some(w.as_str())).collect();
    let e = extract(&sent("Long.pdf", &pdf(&pages)), false);
    // Room for about four pages.
    let r = fit(&[e], 4 * 330 + 50).remove(0);
    assert_eq!(r.note, "Read pages 1 to 4 of 10", "{r:?}");
    assert!(
        r.text.contains("Page 4:") && !r.text.contains("Page 5:"),
        "{}",
        r.text
    );
}

#[test]
fn a_scanned_pdf_is_looked_at_as_pictures_or_said_to_be_unreadable() {
    let bytes = pdf(&[None, None]);
    let blind = read_all(&sent("Scan.pdf", &bytes), false);
    assert_eq!(blind.text, "");
    assert_eq!(blind.note, NOT_READABLE);
    let e = extract(&sent("Scan.pdf", &bytes), true);
    assert_eq!(e.pictures.len(), 2, "{e:?}");
    assert_eq!(e.pictures[0].media_type, "image/jpeg");
    let r = fit(&[e], FREE_CHARS).remove(0);
    assert!(
        r.note.contains("looks like a scan") && r.note.contains("2 of 2 pages"),
        "{}",
        r.note
    );
}

#[test]
fn word_documents_sheets_and_slides_are_read() {
    let d = read_all(&sent("Plan.docx", &docx()), false);
    assert_eq!(d.note, "Read all of it");
    assert!(
        d.text.contains("Garden planner")
            && d.text.contains("Plant the beans \tin May.")
            && d.text.contains("Row A"),
        "{}",
        d.text
    );

    let x = read_all(&sent("Seeds.xlsx", &xlsx()), false);
    assert_eq!(x.note, "Read all of it");
    assert!(
        x.text.contains("Sheet: Seeds\nBeans\t\t12\nPeas\t7"),
        "{}",
        x.text
    );
    assert!(x.text.contains("Sheet: Costs\nTotal\t40"), "{}", x.text);

    let pptx = zip(
        &[
            ("ppt/presentation.xml", b"<p/>"),
            ("ppt/slides/slide2.xml", br#"<p:sld xmlns:a="a" xmlns:p="p"><a:p><a:r><a:t>Second slide</a:t></a:r></a:p></p:sld>"#),
            ("ppt/slides/slide1.xml", br#"<p:sld xmlns:a="a" xmlns:p="p"><a:p><a:r><a:t>First slide</a:t></a:r></a:p></p:sld>"#),
        ],
        true,
    );
    let s = read_all(&sent("Talk.pptx", &pptx), false);
    assert!(
        s.text.find("First slide").unwrap() < s.text.find("Second slide").unwrap(),
        "{}",
        s.text
    );
}

#[test]
fn opendocument_files_and_books_are_read() {
    let odt = zip(
        &[
            ("mimetype", b"application/vnd.oasis.opendocument.text"),
            (
                "content.xml",
                br#"<office:document-content xmlns:office="o" xmlns:text="t"><office:body><office:text>
<text:h>Seed list</text:h><text:p>Beans<text:s/>and<text:tab/>peas</text:p></office:text></office:body></office:document-content>"#,
            ),
        ],
        false,
    );
    let r = read_all(&sent("List.odt", &odt), false);
    assert!(
        r.text.contains("Seed list") && r.text.contains("Beans and\tpeas"),
        "{}",
        r.text
    );

    let ods = zip(
        &[
            ("mimetype", b"application/vnd.oasis.opendocument.spreadsheet"),
            (
                "content.xml",
                br#"<office:document-content xmlns:office="o" xmlns:text="t" xmlns:table="b"><office:body><office:spreadsheet>
<table:table table:name="Plots"><table:table-row><table:table-cell><text:p>North</text:p></table:table-cell><table:table-cell><text:p>4</text:p></table:table-cell></table:table-row></table:table>
</office:spreadsheet></office:body></office:document-content>"#,
            ),
        ],
        false,
    );
    let r = read_all(&sent("Plots.ods", &ods), false);
    assert!(r.text.contains("Sheet: Plots\nNorth\t4"), "{}", r.text);

    let epub = zip(
        &[
            ("mimetype", b"application/epub+zip"),
            ("META-INF/container.xml", br#"<container><rootfiles><rootfile full-path="OEBPS/book.opf"/></rootfiles></container>"#),
            (
                "OEBPS/book.opf",
                br#"<package><manifest><item id="a" href="one.xhtml"/><item id="b" href="two.xhtml"/></manifest><spine><itemref idref="b"/><itemref idref="a"/></spine></package>"#,
            ),
            ("OEBPS/one.xhtml", b"<html><body><p>Chapter one &amp; more</p></body></html>"),
            ("OEBPS/two.xhtml", b"<html><head><style>p{}</style></head><body><p>Opening</p></body></html>"),
        ],
        true,
    );
    let r = read_all(&sent("Book.epub", &epub), false);
    assert!(
        r.text.find("Opening").unwrap() < r.text.find("Chapter one & more").unwrap(),
        "{}",
        r.text
    );
    assert!(!r.text.contains("p{}"));
}

#[test]
fn a_zip_lists_its_files_and_reads_the_text_ones() {
    let z = zip(
        &[
            ("notes/a.txt", b"Water the beans."),
            ("notes/b.md", b"# Peas\nPlant early."),
            ("pic.png", b"\x89PNG\r\n\x1a\n\0\0\0binary"),
            (".hidden", b"secret-ish"),
        ],
        true,
    );
    let r = read_all(&sent("Pack.zip", &z), false);
    assert!(r.text.contains("This zip holds 4 files"), "{}", r.text);
    assert!(r.text.contains("notes/a.txt (16 bytes)"), "{}", r.text);
    assert!(
        r.text.contains("== notes/a.txt ==\nWater the beans."),
        "{}",
        r.text
    );
    assert!(r.text.contains("Plant early."));
    assert!(
        !r.text.contains("binary") && !r.text.contains("secret-ish"),
        "{}",
        r.text
    );
    assert_eq!(r.note, "Read all of it");
}

#[test]
fn text_code_and_tables_are_read_and_binary_is_turned_down() {
    let csv = read_all(&sent("t.csv", b"name,qty\nbeans,4\n"), false);
    assert_eq!(csv.text, "name,qty\nbeans,4");
    let utf16: Vec<u8> = [0xFFu8, 0xFE]
        .into_iter()
        .chain("hi there".encode_utf16().flat_map(u16::to_le_bytes))
        .collect();
    assert_eq!(read_all(&sent("w.txt", &utf16), false).text, "hi there");
    let bom = read_all(&sent("b.txt", b"\xEF\xBB\xBFcaf\xC3\xA9"), false);
    assert_eq!(bom.text, "café");
    let junk = read_all(&sent("x.bin", b"\x00\x01\x02\x03\xFF\xFE\x00"), false);
    assert_eq!(junk.text, "");
    assert!(
        junk.note.starts_with("Couldn't read this kind of file"),
        "{}",
        junk.note
    );
    let old = read_all(&sent("old.doc", b"\xD0\xCF\x11\xE0 old word file"), false);
    assert!(old.note.contains("Save it as a PDF"), "{}", old.note);
    let empty = read_all(&sent("e.txt", b""), false);
    assert_eq!(empty.note, "This file is empty. Pick another one.");
    let big = SentFile {
        name: "huge.txt".into(),
        media_type: String::new(),
        base64: base64::engine::general_purpose::STANDARD.encode(vec![b'a'; MAX_FILE_BYTES + 1]),
    };
    assert!(read_all(&big, false).note.contains("over 10 MB"));
}

#[test]
fn a_long_text_says_it_read_the_first_part() {
    let long = "line of words\n".repeat(5000);
    let r = read_all(&sent("long.txt", long.as_bytes()), false);
    assert_eq!(r.note, "Read the first part of it");
    assert!(r.text.chars().count() <= FREE_CHARS);
}

#[test]
fn files_of_one_message_share_the_room_and_a_short_one_leaves_its_share() {
    let big = "x".repeat(50_000);
    let items = [
        extract(&sent("big.txt", big.as_bytes()), false),
        extract(&sent("small.txt", b"tiny"), false),
    ];
    let r = fit(&items, 10_000);
    assert_eq!(r[1].text, "tiny");
    assert_eq!(r[1].note, "Read all of it");
    assert_eq!(
        r[0].text.chars().count(),
        9_996,
        "the big one gets all the small one left"
    );
    assert_eq!(r[0].note, "Read the first part of it");
}

#[test]
fn names_have_no_folders_and_a_zip_bomb_is_not_unpacked() {
    assert_eq!(clean_name("../../etc/passwd"), "passwd");
    assert_eq!(clean_name("C:\\Users\\x\\My \"file\".txt"), "My file.txt");
    assert_eq!(clean_name("///"), "file");
    // A member that says it unpacks to more than the cap is skipped, not unpacked.
    let zeros = vec![0u8; 30 * 1024 * 1024];
    let z = zip(&[("big.txt", &zeros), ("ok.txt", b"fine")], true);
    assert!(
        z.len() < 200_000,
        "the sample should pack small, was {}",
        z.len()
    );
    let r = read_all(&sent("bomb.zip", &z), false);
    assert!(
        r.text.contains("fine") && !r.text.contains('\u{0}'),
        "{}",
        r.text.len()
    );
}

#[test]
fn sound_says_what_is_missing_when_the_pc_cannot_listen_yet() {
    let wav = b"RIFF\x24\x00\x00\x00WAVEfmt \x10\x00\x00\x00\x01\x00\x01\x00\x80\x3e\x00\x00\x00\x7d\x00\x00\x02\x00\x10\x00data\x00\x00\x00\x00";
    let r = read_all(&sent("note.wav", wav), false);
    assert_eq!(r.text, "");
    assert!(r.note.contains("recording"), "{}", r.note);
}
