#!/usr/bin/env python3
"""
Генератор синтетичного англомовного датасету для пошукового рушія.

Створює файли 1.txt, 2.txt, ... N.txt (за замовчуванням 10 000 файлів, ~220-260 КБ кожен,
разом ~2.4 ГБ). Лише ASCII-літери, тож Tokenizer/ISO_8859_1 у проєкті читають їх без проблем.

Потрібен файл wordlist.txt поруч зі скриптом (80 000 реальних англійських слів за частотою).

Запуск:
    python generate_dataset.py                 # -> ./data
    python generate_dataset.py --out D:\\data --workers 8
"""
import argparse
import bisect
import itertools
import os
import random
import sys
import time
from multiprocessing import Pool

STOP = ("the of and to in a is that for it as was with be by on not he this are or his from at which but have "
        "an had they you were their one all we can her has there been if more when will would who so no she "
        "other its may these than them then into only some what about out up over also after most new two "
        "first where much could like through back many even between because while such both under same "
        "during before another still each just").split()

COMMON = ("people year time way day man thing woman life child world school state family student group country "
          "problem hand part place case week company system program question work government number night point "
          "home water room mother area money story fact month lot right study book eye job word business issue "
          "side kind head house service friend father power hour game line end member law car city community "
          "name president team minute idea body information face others level office door health person art war "
          "history party result change morning reason research girl guy moment air teacher force education "
          "good great small large young old long high different important local late early possible public "
          "real best better strong clear simple common recent final main major special free full open human "
          "make take give find tell become leave feel bring begin keep hold write stand hear let mean set meet "
          "run move live believe happen provide include continue build learn change lead understand watch follow "
          "stop create speak read allow add spend grow open walk win offer remember love consider appear buy wait "
          "serve die send expect stay fall cut reach kill remain suggest raise pass sell require report decide pull").split()

TOPICS = {
    "space": "space galaxy star planet orbit rocket astronaut satellite moon mars telescope cosmos nebula comet "
             "asteroid spaceship shuttle launch gravity universe alien mission lunar solar eclipse cosmonaut "
             "capsule probe station meteor supernova blackhole constellation voyage interstellar spacecraft "
             "movie film cinema director actor scene screenplay trailer sequel hollywood".split(),
    "ocean": "ocean sea wave tide coral reef whale dolphin shark fish beach island harbor ship sailor captain "
             "anchor current storm lighthouse shore marine submarine diver depth salt fisherman net boat "
             "voyage port cargo bay gulf lagoon seaweed plankton turtle octopus squid".split(),
    "cooking": "recipe kitchen oven flour sugar butter egg onion garlic pepper sauce soup salad bread cheese "
               "chicken beef pasta rice potato tomato spice herb baking roast fry boil simmer chef restaurant "
               "dinner lunch breakfast dessert cake cookie chocolate vanilla cream honey lemon olive".split(),
    "sports": "football soccer basketball tennis baseball hockey golf match tournament league champion coach "
              "player team goal score referee stadium season trophy medal athlete race sprint marathon "
              "swimming cycling boxing training fitness defense offense captain penalty title final".split(),
    "medicine": "doctor patient hospital disease virus vaccine medicine treatment surgery symptom diagnosis "
                "therapy clinic nurse drug infection cancer heart blood brain bone immune pharmacy dose "
                "recovery chronic acute screening cell gene protein antibiotic fever pain health".split(),
    "history": "empire kingdom king queen emperor revolution battle army soldier treaty ancient medieval "
               "dynasty castle knight crusade colony republic parliament monarchy conquest civilization "
               "archaeology ruins artifact museum scroll manuscript century era legend pharaoh roman".split(),
    "technology": "computer software hardware network internet server database algorithm processor memory "
                  "code program developer startup robot sensor battery wireless cloud encryption browser "
                  "keyboard screen pixel digital data machine learning model chip silicon device mobile".split(),
    "music": "music song melody rhythm guitar piano violin drum orchestra concert band singer album lyrics "
             "composer symphony jazz rock opera chorus harmony tempo studio record festival microphone "
             "tune ballad instrument concerto note chord stage audience".split(),
    "nature": "forest mountain river lake valley meadow tree flower leaf bird animal wolf bear deer eagle "
              "rain snow wind canyon desert glacier waterfall wildlife jungle soil seed root branch "
              "season spring summer autumn winter climate ecosystem habitat species".split(),
    "finance": "bank market stock investor price profit loss budget tax economy trade currency inflation "
               "credit loan mortgage asset fund dividend revenue cost growth recession wealth income "
               "insurance bond shares merchant auction contract customer invoice capital".split(),
    "travel": "travel journey airport flight hotel passport tourist visa luggage train station ticket "
              "destination guide map museum cathedral market street capital village countryside road "
              "highway adventure backpack hostel cruise ferry border customs souvenir itinerary".split(),
    "science": "experiment laboratory theory physics chemistry biology molecule atom energy particle "
               "equation hypothesis research scientist discovery formula element reaction quantum "
               "microscope evolution genetics fossil mineral magnet electron laser radiation".split(),
    "education": "school teacher student classroom lesson exam university college professor degree "
                 "library homework curriculum lecture scholarship textbook semester diploma campus "
                 "tutor essay grammar mathematics literature seminar thesis graduate".split(),
    "law": "court judge lawyer trial jury verdict evidence witness crime police prison sentence appeal "
           "contract rights constitution statute attorney defendant plaintiff justice legislation "
           "regulation penalty fraud testimony investigation warrant".split(),
}

ZIPF_S = 1.15          # трохи крутіше за 1.0: менше унікальних слів у документі
N_CLUSTERS = 3000      # "тематичні кластери" зі слів середньої частоти
CLUSTER_SIZE = 180

WORDS = None           # весь словник у порядку спадання частоти (реальна англійська)
GLOBAL_CUM = None
CLUSTERS = None
HAND = None


def load_vocab(path, vocab):
    with open(path, encoding="ascii") as f:
        words = [w.strip() for w in f if w.strip()][:vocab]
    if len(words) < 1000:
        sys.exit(f"Словник {path} надто малий або відсутній")
    return words


def init_worker(wordlist, vocab):
    global WORDS, GLOBAL_CUM, CLUSTERS, HAND
    WORDS = load_vocab(wordlist, vocab)
    # закон Ципфа: імовірність слова ~ 1/ранг^s
    GLOBAL_CUM = list(itertools.accumulate(1.0 / r ** ZIPF_S for r in range(1, len(WORDS) + 1)))
    rng = random.Random(777)
    lo, hi = min(300, len(WORDS) // 4), len(WORDS)
    CLUSTERS = []
    for _ in range(N_CLUSTERS):
        c = rng.sample(WORDS[lo:hi], min(CLUSTER_SIZE, hi - lo))
        CLUSTERS.append((c, list(itertools.accumulate(1.0 / r ** 0.7 for r in range(1, len(c) + 1)))))
    HAND = {k: (v, list(itertools.accumulate(1.0 / r ** 0.8 for r in range(1, len(v) + 1))))
            for k, v in TOPICS.items()}


def pick(rng, pool, k):
    words, cum = pool
    return rng.choices(words, cum_weights=cum, k=k)


def make_doc(index, target_bytes, seed):
    rng = random.Random(seed * 1_000_003 + index)
    main, second = rng.sample(list(HAND), 2)
    my_clusters = [CLUSTERS[rng.randrange(N_CLUSTERS)] for _ in range(4)]
    title_words = pick(rng, HAND[main], 2) + pick(rng, my_clusters[0], 2)
    chunks = []
    size = 0
    while size < target_bytes:
        n = 4000
        n_main, n_second = int(n * 0.10), int(n * 0.04)
        n_clu = int(n * 0.22)
        n_glob = n - n_main - n_second - n_clu
        words = (pick(rng, (WORDS, GLOBAL_CUM), n_glob) + pick(rng, HAND[main], n_main) +
                 pick(rng, HAND[second], n_second))
        for c in my_clusters:
            words += pick(rng, c, n_clu // len(my_clusters))
        rng.shuffle(words)
        out = []
        i, total = 0, len(words)
        para_left = rng.randint(4, 9)
        while i < total:
            ln = rng.randint(7, 24)
            sent = words[i:i + ln]
            i += ln
            if len(sent) > 9 and rng.random() < 0.6:
                pos = rng.randint(3, len(sent) - 3)
                sent[pos] += ","
            if len(sent) > 12 and rng.random() < 0.25:
                pos = rng.randint(4, len(sent) - 3)
                sent[pos] += ","
            for j in range(1, len(sent)):                  # "власні назви" з великої літери
                if rng.random() < 0.025:
                    sent[j] = sent[j].capitalize()
            sent[0] = sent[0].capitalize()
            end = rng.choice((".", ".", ".", ".", ".", "?", "!"))
            out.append(" ".join(sent) + end)
            para_left -= 1
            if para_left <= 0:
                out.append("\n\n")
                para_left = rng.randint(4, 9)
            else:
                out.append(" ")
        block = "".join(out)
        chunks.append(block)
        size += len(block)
    title = " ".join(w.capitalize() for w in title_words) + "\n\n"
    return title + "".join(chunks)


def worker(args):
    path, index, target, seed = args
    text = make_doc(index, target, seed)
    with open(os.path.join(path, f"{index}.txt"), "w", encoding="ascii", newline="\n") as f:
        f.write(text)
    return len(text)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--out", default="data")
    p.add_argument("--count", type=int, default=10_000)
    p.add_argument("--min-kb", type=int, default=220)
    p.add_argument("--max-kb", type=int, default=260)
    p.add_argument("--workers", type=int, default=os.cpu_count() or 2)
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--wordlist", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "wordlist.txt"))
    p.add_argument("--vocab", type=int, default=50_000, help="скільки найчастіших слів використовувати")
    a = p.parse_args()

    os.makedirs(a.out, exist_ok=True)
    sizes = random.Random(a.seed)
    jobs = [(a.out, i, sizes.randint(a.min_kb, a.max_kb) * 1000, a.seed) for i in range(1, a.count + 1)]

    start = time.time()
    total = 0
    with Pool(a.workers, initializer=init_worker, initargs=(a.wordlist, a.vocab)) as pool:
        for done, n in enumerate(pool.imap_unordered(worker, jobs, chunksize=8), 1):
            total += n
            if done % 200 == 0 or done == a.count:
                print(f"\r{done}/{a.count} файлів, {total / 1e9:.2f} ГБ, {time.time() - start:.0f} с", end="", flush=True)
    print(f"\nГотово: {a.count} файлів у '{a.out}', {total / 1e9:.2f} ГБ ({total / 2**30:.2f} ГіБ)")


if __name__ == "__main__":
    main()
