from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.platypus import (
    KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle,
)
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "Machine-Vision-Camera-使用說明.pdf"
FONT_PATH = Path(r"C:\Windows\Fonts\msjh.ttc")


def register_fonts():
    pdfmetrics.registerFont(TTFont("Manual", str(FONT_PATH), subfontIndex=0))
    pdfmetrics.registerFont(TTFont("ManualBold", str(FONT_PATH), subfontIndex=0))


def build_styles():
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle("title", parent=base["Title"], fontName="ManualBold", fontSize=25,
                                leading=33, alignment=TA_CENTER, textColor=colors.HexColor("#273B57"), spaceAfter=6),
        "subtitle": ParagraphStyle("subtitle", parent=base["Normal"], fontName="Manual", fontSize=11,
                                   leading=17, alignment=TA_CENTER, textColor=colors.HexColor("#526274")),
        "h1": ParagraphStyle("h1", parent=base["Heading1"], fontName="ManualBold", fontSize=17,
                               leading=23, textColor=colors.HexColor("#273B57"), spaceBefore=4, spaceAfter=8),
        "h2": ParagraphStyle("h2", parent=base["Heading2"], fontName="ManualBold", fontSize=12.5,
                               leading=18, textColor=colors.HexColor("#006F8F"), spaceBefore=9, spaceAfter=5),
        "body": ParagraphStyle("body", parent=base["BodyText"], fontName="Manual", fontSize=9.7,
                                leading=15.2, textColor=colors.HexColor("#202A35"), spaceAfter=5),
        "small": ParagraphStyle("small", parent=base["BodyText"], fontName="Manual", fontSize=8.5,
                                 leading=12.5, textColor=colors.HexColor("#445262")),
        "callout": ParagraphStyle("callout", parent=base["BodyText"], fontName="Manual", fontSize=9.4,
                                   leading=14.5, textColor=colors.HexColor("#203244"), spaceAfter=0),
        "step": ParagraphStyle("step", parent=base["BodyText"], fontName="Manual", fontSize=9.6,
                                leading=15, leftIndent=6, firstLineIndent=-6, textColor=colors.HexColor("#202A35"), spaceAfter=4),
        "table": ParagraphStyle("table", parent=base["BodyText"], fontName="Manual", fontSize=8.5,
                                 leading=12.3, textColor=colors.HexColor("#202A35")),
    }


def p(text, style):
    return Paragraph(text, style)


def box(text, styles, tint="#EAF5F8", border="#8CC8D5"):
    table = Table([[p(text, styles["callout"])]], colWidths=[174 * mm])
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor(tint)),
        ("BOX", (0, 0), (-1, -1), 0.65, colors.HexColor(border)),
        ("LEFTPADDING", (0, 0), (-1, -1), 10), ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8), ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    return table


def section_table(rows, styles, widths=(43 * mm, 131 * mm)):
    data = [[p(a, styles["table"]), p(b, styles["table"])] for a, b in rows]
    table = Table(data, colWidths=list(widths), repeatRows=0)
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (0, -1), colors.HexColor("#EFF5F8")),
        ("GRID", (0, 0), (-1, -1), 0.35, colors.HexColor("#B9CAD4")),
        ("LEFTPADDING", (0, 0), (-1, -1), 7), ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 6), ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]))
    return table


def footer(canvas, doc):
    canvas.saveState()
    canvas.setStrokeColor(colors.HexColor("#B9CAD4"))
    canvas.line(18 * mm, 14 * mm, A4[0] - 18 * mm, 14 * mm)
    canvas.setFont("Manual", 8)
    canvas.setFillColor(colors.HexColor("#687786"))
    canvas.drawString(18 * mm, 8.5 * mm, "Machine Vision Camera - 使用者操作說明")
    canvas.drawRightString(A4[0] - 18 * mm, 8.5 * mm, f"第 {doc.page} 頁")
    canvas.restoreState()


def make_pdf():
    register_fonts()
    s = build_styles()
    doc = SimpleDocTemplate(
        str(OUT), pagesize=A4, leftMargin=18 * mm, rightMargin=18 * mm,
        topMargin=17 * mm, bottomMargin=20 * mm, title="Machine Vision Camera 使用說明",
        author="UPR",
    )
    story = []

    # Page 1
    story += [Spacer(1, 20 * mm), p("Machine Vision Camera", s["title"]),
              p("Android 遠端檢測相機 + Windows / 網頁控制端", s["subtitle"]), Spacer(1, 8 * mm)]
    story.append(box("<b>這份手冊只說明使用方式。</b><br/>手機端負責取像與拍照；Windows 控制端或瀏覽器可遠端預覽、調整相機、拍照與追蹤 TCP 圓球。", s))
    story += [Spacer(1, 8 * mm), p("快速開始", s["h1"])]
    quick = [
        ("1", "手機開啟 <b>Machine Vision Camera</b>。程式會預設啟動 Camera 0。"),
        ("2", "電腦與手機連到同一個 Wi-Fi。電腦端輸入手機 IP，控制連接埠固定為 <b>8765</b>，按 Connect。"),
        ("3", "在電腦端選擇相機、預覽解析度與 FPS。畫面出現後即可操作參數、ROI 或拍照。"),
        ("4", "需要瀏覽器控制時，在電腦瀏覽器開啟 <b>http://手機IP:8787</b>。"),
    ]
    story.append(section_table(quick, s, widths=(12 * mm, 162 * mm)))
    story += [Spacer(1, 8 * mm), p("開始前確認", s["h2"])]
    story.append(section_table([
        ("手機", "已安裝 APK，並授予相機與相片儲存權限。手機可直放或橫放；電腦端可在顯示設定中旋轉畫面。"),
        ("網路", "控制電腦與手機必須在同一個區域網路。若無法連線，先確認手機 IP 是否變更。"),
        ("控制連接埠", "一般使用固定為 8765。Android 無線偵錯的 ADB 連接埠與本程式無關，不要填入這裡。"),
        ("儲存位置", "手機照片：DCIM/Machine Vision Camera。Windows 接收的照片：圖片/Machine Vision Camera App。"),
    ], s))
    story += [Spacer(1, 9 * mm), p("三個使用端", s["h2"])]
    story.append(section_table([
        ("手機 APK", "就近確認畫面、切鏡頭、手動拍照與設定傳輸。"),
        ("Windows 控制端", "最完整的遠端預覽、參數、ROI、追蹤與照片接收操作。"),
        ("網頁控制端", "免安裝 Python 的遠端操作方式，適合一般電腦或臨時控制。"),
    ], s))
    story.append(PageBreak())

    # Page 2
    story += [p("手機端操作", s["h1"]),
              p("畫面上方保留相機選擇與即時預覽；拍照鍵位於預覽下方。其餘較少使用的設定收在右上角選單，避免遮住畫面。", s["body"])]
    story += [p("相機與預覽", s["h2"])]
    story.append(section_table([
        ("Camera 0", "每次開啟程式自動啟用。需要其他鏡頭時，使用相機下拉選單切換。"),
        ("Search hidden", "手動掃描可能被廠商隱藏的 Camera2 相機 ID。掃描完成後會列在相機選單中；不同手機結果不同。"),
        ("Camera ON / OFF", "ON 啟動目前選擇的鏡頭；OFF 停止相機工作。手機預覽可關閉，但相機必須保持 ON 才能讓電腦預覽或拍照。"),
        ("More 選單", "可設定拍照尺寸、Original size output、RAW/DNG、YUV 灰階預覽、傳送解析度與傳送 FPS。"),
    ], s))
    story += [p("拍照與尺寸", s["h2"])]
    story.append(box("<b>預覽尺寸與拍照尺寸是分開的。</b><br/>Wi-Fi 預覽可以使用較小解析度降低延遲；拍照仍依「Photo size」或「Original size output」輸出。例如選 4080 x 3072 時，照片會以該尺寸儲存，不會被預覽的 720 x 960 限制。", s, "#FFF7E6", "#E8B45A"))
    story += [Spacer(1, 5 * mm)]
    story.append(section_table([
        ("Take photo", "拍照時預覽短暫變黑再恢復，代表已送出拍照與儲存。照片名稱會包含日期與秒數。"),
        ("Photo size", "從相機實際支援的 JPEG 尺寸中選擇。部分鏡頭與模式不提供全部尺寸。"),
        ("Original size output", "固定使用所選的原始相片尺寸，例如 4080 x 3072。這不是把數位變焦後的畫面裁成 1/10 大小。"),
        ("RAW / DNG", "若鏡頭與手機廠商實作支援，可同時保留較少處理的 DNG 檔；部分隱藏鏡頭可能不提供 RAW。"),
        ("YUV preview", "提供偏原始、低處理的灰階預覽給追蹤用途；它不是 RAW 相片。切換 RAW/YUV 後若畫面異常，先關閉相機再重新開啟。"),
    ], s))
    story += [p("省電與發熱", s["h2"]),
              p("長時間由電腦控制時，可關閉手機端預覽或啟用黑畫面/最低亮度。這可省下螢幕耗電，但相機感光元件、ISP 與 Wi-Fi 傳輸仍會工作。要明顯降溫，優先降低傳送解析度或 FPS。", s["body"])]
    story.append(PageBreak())

    # Page 3
    story += [p("自動、手動與參數", s["h1"]),
              p("手機與電腦共用同一組設定。系統會同步目前狀態，但正在拖拉或輸入數字的一端會暫時保留本地操作，避免兩端互相覆寫。", s["body"])]
    story += [p("AUTO / MANUAL", s["h2"])]
    story.append(section_table([
        ("Overall AUTO", "相機自行計算 ISO、曝光、對焦與白平衡。畫面上的數字會持續顯示即時讀值，但不強制改動相機。"),
        ("Overall MANUAL", "顯示全部可調選項並使用已選擇的手動值。開啟相機時，先前儲存的手動參數會重新套用。"),
        ("個別 Auto", "ISO、曝光時間、Focus D、White balance 各有獨立 Auto 開關。鎖住某一項後，其他仍可自動補償，例如固定 ISO 後讓曝光時間自動調整。"),
    ], s))
    story += [p("調整方式", s["h2"])]
    story.append(section_table([
        ("滑桿", "拖曳時先顯示數值，放開後立即套用。電腦端與手機端均會同步。"),
        ("數字輸入", "直接在每列右側輸入；按 Enter 後套用並離開輸入狀態。單位僅顯示，不能修改。"),
        ("範圍保護", "每個鏡頭都有自己的 ISO、曝光、對焦與最大變焦範圍。輸入超出範圍時，程式會自動採用最接近的可用值並回寫顯示。"),
    ], s))
    story += [p("參數對照", s["h2"])]
    story.append(section_table([
        ("ISO", "感光增益。數字越大越亮，但雜訊通常越多。"),
        ("Exposure", "曝光時間，以 us 或 ms 顯示。長曝光會降低可用 FPS，移動物體也較容易模糊。"),
        ("Focus D", "屈光度。數字越大通常對焦越近；各鏡頭範圍不同。"),
        ("Zoom", "依目前鏡頭支援範圍控制。高倍率可能是數位裁切，不一定是純光學變焦。"),
        ("White balance", "色溫 2000 - 8000 K。若個別白平衡 Auto 開啟，色溫讀值只是目前結果；關閉 Auto 才會固定指定色溫。"),
    ], s))
    story += [Spacer(1, 5 * mm), box("<b>數值與照片 EXIF 不完全相同時：</b><br/>畫面顯示的是 CaptureResult 的即時回報；相簿的 EXIF 是最終 JPEG 寫入的拍攝資訊。廠商 ISP 可能為 AE/HDR/多幀處理選擇不同的最終記錄值。手動模式與關閉個別 Auto 可讓兩者更接近。", s, "#F2EFFA", "#A99BD3")]
    story.append(PageBreak())

    # Page 4
    story += [p("Windows 與網頁控制", s["h1"]),
              p("Windows 控制端適合長時間檢測與 ROI 量測。網頁控制端的功能相近，但以瀏覽器顯示與操作。", s["body"])]
    story += [p("連線與預覽", s["h2"])]
    story.append(section_table([
        ("Windows 連線", "開啟 Windows Controller，在 Phone IP 輸入手機 IP、Port 輸入 8765，按 Connect。成功後會載入手機目前的相機、模式與參數。"),
        ("選鏡頭", "選取 Camera ID 後按 Select camera。需要尋找廠商未列出的鏡頭時按 Search hidden，掃描完成後再從下拉選單選擇。"),
        ("預覽來源", "可分別開關手機端與 PC 端 Preview。手機螢幕關閉預覽時，電腦仍可接收畫面；反之亦可只在手機查看。"),
        ("畫面顯示", "Live Preview 可選 FIT、STRETCH、MANUAL、ORIGINAL，亦可旋轉 0/90/180/270 度與左右/上下翻轉。FIT 維持比例並貼齊可用邊界，不裁切也不拉伸。"),
        ("照片回傳", "從 PC 端拍照會請手機以選定相片尺寸拍攝，完成後自動傳回電腦的 圖片/Machine Vision Camera App。"),
    ], s))
    story += [p("傳送解析度與 FPS", s["h2"])]
    story.append(section_table([
        ("用途", "低延遲優先：480 x 640 或 720 x 960。細節優先：900 x 1200 或 1080 x 1440。拍照不受此設定影響。"),
        ("FPS 選項", "可選 5、10、15、20、30、60 FPS。實際 FPS 顯示在預覽下方或手機狀態中。"),
        ("60 FPS 現況", "目前一般 Camera2 預覽工作階段在 Camera 0 實測最高約 30 FPS。選擇 60 時程式會安全地回報並使用實際可用的 30 FPS；這不是連線故障。真正 60 FPS 需要獨立的高幀率相機工作階段，會與目前的全功能拍照/RAW/YUV 模式分開處理。"),
        ("低 FPS", "曝光時間本身會限制影格率。例如手動曝光 50 ms 的理論上限約 20 FPS；降低曝光或開啟 Auto 可提高更新率。"),
    ], s))
    story += [p("網頁控制端", s["h2"]),
              p("在同一個網路的電腦開啟 <b>http://手機IP:8787</b>。先連線，再使用相機選擇、Search hidden、Camera ON/OFF、參數與拍照按鈕。若電腦不方便安裝程式，優先使用此方式。", s["body"])]
    story.append(PageBreak())

    # Page 5
    story += [p("TCP 圓球 ROI 與位移追蹤", s["h1"]),
              p("ROI 用來測量 TCP 圓球是否偏離參考位置。畫面上的綠圈是目標區域，黃十字是 ROI 中心，紅點是追蹤到的中心。追蹤前三者應盡量重合。", s["body"])]
    story += [p("建立 ROI", s["h2"])]
    story.append(section_table([
        ("1. 畫圓", "在 Live Preview 從左到右或由上到下拖曳，拉出圓的直徑。程式先建立正圓，避免斜向拖曳造成歪圓。"),
        ("2. 微調", "Circle - / Circle + 調整半徑；也可輸入 Diameter px L/R 與 U/D 微調水平和垂直直徑。拖曳綠圈可移動位置。"),
        ("3. 量尺", "Ball diameter (mm) 預設 10.00。輸入實際球直徑後，程式用目前圓的像素直徑換算 mm/px。也可用 2-point calibrate 配合已知距離校正。"),
        ("4. 參考點", "確認綠圈、黃十字與紅點共心後按 Set reference，將當下中心設為零點。"),
        ("5. 開始追蹤", "勾選 Track 的瞬間，程式擷取綠圈內的目標特徵作為模板，再於後續畫面搜尋。移動物件後，紅點應跟著目標；Now 顯示目前偏移，Max 顯示歷史最大偏移。"),
    ], s))
    story += [p("操作重點", s["h2"])]
    story.append(section_table([
        ("Clear data", "只清除 Max 最大偏移，保留目前 ROI、參考點與追蹤設定。"),
        ("Clear circle", "清除 ROI 圓與追蹤目標，需重新建立 ROI。"),
        ("Zoom", "變焦會改變球在畫面中的像素大小與位置。變焦後請重新確認 ROI 直徑與中心，再重新 Set reference 和開始 Track。"),
        ("追蹤可靠度", "讓圓球邊緣清楚、固定對焦、避免嚴重反光與背景中相似圓形。ROI 要剛好包住球，過大會讓背景干擾增加。"),
    ], s))
    story += [p("常見問題", s["h2"])]
    problems = [
        ("連不上", "確認同一 Wi-Fi、手機 IP 正確、控制 Port 是 8765，而不是 ADB 偵錯連接埠。"),
        ("PC 沒畫面", "確認 Camera ON、PC Preview 已開啟，並先降為 720 x 960 / 30 FPS 測試。"),
        ("數字回跳", "確認該項 Auto 已關閉再手動調整；輸入後按 Enter。"),
        ("追蹤跳到別處", "重新畫小而準確的 ROI，確認目標在圈內後才啟用 Track，並降低背景相似物與反光。"),
        ("手機過熱", "降低傳送解析度/FPS、關閉不需要的手機螢幕預覽、避免長時間高 ISO 或長曝光。"),
    ]
    story.append(section_table(problems, s))
    story += [Spacer(1, 8 * mm), box("<b>建議的 TCP 檢測流程：</b><br/>固定手機與照明 - 選擇鏡頭 - 先用 Auto 對焦與曝光 - 切換需要鎖定的手動項目 - 建立並校正 ROI - Set reference - Track - 讀取 Now / Max 位移。", s, "#EAF6EC", "#92C59A")]

    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    print(OUT)


if __name__ == "__main__":
    make_pdf()
