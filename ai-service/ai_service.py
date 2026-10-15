from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
from typing import List, Optional
from datetime import datetime
import uvicorn
import torch
from PIL import Image, ImageOps
from transformers import DonutProcessor, VisionEncoderDecoderModel, AutoTokenizer
import re
import io
import os
import traceback

from wcm_spade import WCMSpade
import budget_advisor

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
app = FastAPI()

MODEL_PATH = os.path.join(BASE_DIR, "Version3", "donut_result")
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"

IMAGE_HEIGHT = 1280
IMAGE_WIDTH = 960

print(f"Đang khởi động AI DONUT Engine trên thiết bị: [{DEVICE.upper()}]...")
if not os.path.exists(MODEL_PATH):
    raise RuntimeError(f"Không tìm thấy weights tại {MODEL_PATH}")

processor = DonutProcessor.from_pretrained("naver-clova-ix/donut-base")
try:
    processor.tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH)
except Exception:
    pass

model = VisionEncoderDecoderModel.from_pretrained(MODEL_PATH).to(DEVICE)
model.eval()
print("AI DONUT Engine đã tải xong!")


def extract_final_cost(raw_cost_str: str) -> int:
    clean_str = raw_cost_str.replace('.', '').replace(',', '')
    numbers = re.findall(r'\d+', clean_str)
    if numbers:
        return max(int(num) for num in numbers)
    return 0


def clean_vietnamese_text(sequence: str) -> str:
    sequence = re.sub(r'([,.;])([A-ZĐa-zà-ỹ])', r'\1 \2', sequence)
    toned_vowels = "áắấéếíóốớúứýàằầèềìòồờùừỳảẳẩẻểỉỏổởủửỷãẵẫẽễĩõỗỡũữỹạặậẹệịọộợụựỵ"
    toned_vowels += toned_vowels.upper()
    sequence = re.sub(r'(["' + toned_vowels + r'])(["' + toned_vowels + r'])', r'\1 \2', sequence)

    all_vowels = "aăâeêioôơuưyáàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩịóòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵĐđ"
    all_vowels += all_vowels.upper()
    tails = ["c", "ch", "m", "n", "ng", "nh", "p", "t", "i", "y", "a", "o", "u"]
    tails += [t.upper() for t in tails]
    tail_pattern = "|".join(tails)
    pattern = r"([" + all_vowels + r"])\s+(" + tail_pattern + r")\b"

    old_sequence = ""
    while old_sequence != sequence:
        old_sequence = sequence
        sequence = re.sub(pattern, r"\1\2", sequence)

    return sequence


@app.get("/health")
def health_check():
    return {"status": "ok"}


@app.post("/donut/scan")
async def extract_receipt_info(image: UploadFile = File(...)):
    print(f"\nĐã nhận hóa đơn từ App: {image.filename}")
    try:
        contents = await image.read()
        if not contents or len(contents) == 0:
            print("LỖI: File ảnh rỗng (0 bytes)!")
            raise HTTPException(status_code=400, detail="File ảnh nhận được bị rỗng (0 bytes)")
            
        pil_img = Image.open(io.BytesIO(contents))
        # Khắc phục lỗi xoay ảnh EXIF từ Camera Android & convert sang RGB chuẩn
        pil_img = ImageOps.exif_transpose(pil_img).convert("RGB")
        print(f"Đã mở ảnh thành công! Kích thước: {pil_img.size}")
    except HTTPException:
        raise
    except Exception as e:
        print(f"Lỗi đọc/convert file ảnh chi tiết: {e}")
        print(traceback.format_exc())
        raise HTTPException(status_code=400, detail=f"Lỗi đọc file ảnh: {str(e)}")

    pixel_values = processor(
        pil_img,
        return_tensors="pt",
        do_resize=True,
        size={"height": IMAGE_HEIGHT, "width": IMAGE_WIDTH},
        do_align_long_axis=True
    ).pixel_values.to(DEVICE)
    
    start_token_id = processor.tokenizer.convert_tokens_to_ids("<s_seller>")
    outputs = model.generate(
        pixel_values,
        max_length=768,
        decoder_input_ids=torch.tensor([[start_token_id]]).to(DEVICE),
        pad_token_id=processor.tokenizer.pad_token_id,
        eos_token_id=processor.tokenizer.eos_token_id,
        use_cache=True,
    )

    sequence = processor.batch_decode(outputs)[0]
    sequence = sequence.replace(processor.tokenizer.eos_token, "").replace(processor.tokenizer.pad_token, "")
    sequence = re.sub(r"<pad>", "", sequence)
    sequence = clean_vietnamese_text(sequence)

    try:
        prediction = processor.token2json(sequence)
        if "seller" in prediction and "total_cost" in prediction["seller"]:
            raw_cost = prediction["seller"]["total_cost"]
            prediction["seller"]["total_cost_number"] = extract_final_cost(raw_cost)
    except Exception as e:
        print(f"Lỗi parse JSON từ Donut: {e}")
        prediction = {"raw_text": sequence}
        
    print(f"Bóc tách thành công: {prediction}")
    return prediction


class SpadeTx(BaseModel):
    category: str
    amount: float = 0.0
    date: str = ""
    isExpense: bool = True

class SpadeRequest(BaseModel):
    transactions: List[SpadeTx]
    max_gap_days: int = 7
    min_sup_factor: float = 0.5
    top_n: int = 10
    alpha: float = 0.5
    beta: float = 0.5

class RecommendRequest(BaseModel):
    transactions: List[SpadeTx]
    target: float = 0.0          
    current: float = 0.0         
    months_left: int = 0        
    monthly_income: Optional[float] = None  
    needed_monthly: Optional[float] = None  
    alpha: float = 0.5
    min_sup_factor: float = 0.4
    max_cut_ratio: float = 0.30


def _parse_date(s: str):
    if not s:
        return None
    s = s.strip().replace("Z", "")
    try:
        return datetime.fromisoformat(s)
    except ValueError:
        try:
            return datetime.fromisoformat(s[:10])
        except ValueError:
            return None


def _build_user_sequences(exp):
    from collections import defaultdict
    by_month = defaultdict(lambda: defaultdict(set))  
    price_sum = defaultdict(float)
    price_cnt = defaultdict(int)
    for d, cat, amt in exp:
        by_month[(d.year, d.month)][d.toordinal()].add(cat)
        price_sum[cat] += amt
        price_cnt[cat] += 1
    sequences = []
    for _, days in by_month.items():
        seq = [tuple(sorted(days[day])) for day in sorted(days.keys())]
        sequences.append(seq)
    item_prices = {c: price_sum[c] / price_cnt[c] for c in price_sum}
    return sequences, item_prices


@app.post("/spade/analyze")
def analyze_patterns(req: SpadeRequest):
    print(f"\nWCM-SPADE nhận {len(req.transactions)} giao dịch")
    exp = []
    for t in req.transactions:
        d = _parse_date(t.date)
        if t.isExpense and d is not None:
            exp.append((d, t.category, max(t.amount, 0.0)))
    exp.sort(key=lambda x: x[0])
    
    if len(exp) < 3:
        return {
            "patterns": [],
            "message": "Chưa đủ dữ liệu chuỗi (cần ít nhất 3 giao dịch hợp lệ)"
        }

    sequences, item_prices = _build_user_sequences(exp)
    if len(sequences) < 1:
        return {"patterns": [], "message": "Không đủ dữ liệu chuỗi"}

    engine = WCMSpade(min_sup=req.min_sup_factor, alpha=req.alpha, max_pattern_length=4)
    result = engine.mine(sequences, item_prices)

    seq_patterns = [p for p in result.patterns if len(p.itemsets) >= 2 or sum(len(it) for it in p.itemsets) >= 2]
    patterns = []
    for p in seq_patterns[: req.top_n]:
        human = " → ".join("+".join(str(i) for i in it) for it in p.itemsets)
        cover = (p.raw_sup / result.n_sequences * 100) if result.n_sequences else 0.0
        patterns.append({
            "pattern": human,
            "count": p.raw_sup,
            "weight": round(p.wsup, 4),
            "wsupport": f"{cover:.1f}%",
        })

    print(f"Trả về {len(patterns)} mẫu Cá nhân hóa.")
    return {
        "patterns": patterns,
        "effectiveMinSup": result.effective_min_sup,
        "meanW": round(result.mean_w, 4),
        "nSequences": result.n_sequences,
    }


@app.post("/spade/recommend")
def recommend_budget(req: RecommendRequest):
    print(f"\nBudgetAdvisor nhận {len(req.transactions)} giao dịch")
    txs = [
        {"category": t.category, "amount": t.amount, "date": t.date, "isExpense": t.isExpense}
        for t in req.transactions
    ]
    if not txs:
        return {
            "trends": [],
            "tagStats": [],
            "goal": {"neededMonthly": 0, "monthlyIncome": req.monthly_income or 0, "monthlyExpense": 0, "surplus": 0, "gap": 0},
            "recommendations": [],
            "summary": "Bạn chưa có giao dịch. Hãy nhập chi tiêu để AI tính toán nhé!"
        }

    result = budget_advisor.advise(
        txs,
        target=req.target,
        current=req.current,
        months_left=req.months_left,
        monthly_income=req.monthly_income,
        needed_monthly=req.needed_monthly,
        alpha=req.alpha,
        min_sup_factor=req.min_sup_factor,
        max_cut_ratio=req.max_cut_ratio,
    )
        
    print(f"BudgetAdvisor: {len(result.get('recommendations', []))} đề xuất")
    return result


class UserRFM(BaseModel):
    user_id: str
    recency: int    
    frequency: int   
    monetary: float  

class AdminSegmentRequest(BaseModel):
    users: List[UserRFM]

@app.post("/admin/segment")
def segment_users(req: AdminSegmentRequest):
    print(f"\n Đang phân lớp {len(req.users)} khách hàng RFM...")
    
    if not req.users:
        return {"segments": []}

    result_segments = []
    
    for u in req.users:
        if u.recency > 120:
            label = "CHURN"
        elif u.recency > 30:
            label = "AT-RISK"
        elif u.monetary > 10000000 and u.frequency >= 2:
            label = "VIP"
        else:
            label = "ACTIVE"
            
        result_segments.append({
            "user_id": u.user_id,
            "label": label
        })
        
    return {"segments": result_segments}

if __name__ == "__main__":
    uvicorn.run("ai_service", host="0.0.0.0", port=8000, reload=True)