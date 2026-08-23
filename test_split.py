#!/usr/bin/env python3
import re

def splitIntoSpeechChunks(text: str) -> list:
    if not text:
        return []
    # Split into paragraphs by one or more blank lines
    paragraphs = re.split(r'\n\s*\n', text)
    chunks = []
    for paragraph in paragraphs:
        trimmed = paragraph.strip()
        if not trimmed:
            continue
        # Split paragraph into sentences: keep punctuation with sentence
        # Using split by lookbehind for punctuation: (?<=[。！？.!?])
        sentences = re.split(r'(?<=[。！？.!?])', trimmed)
        # Filter out empty strings
        sentence_list = [s for s in sentences if s]
        current_chunk = ""
        for sentence in sentence_list:
            if len(current_chunk) + len(sentence) <= 200:
                current_chunk += sentence
            else:
                if current_chunk:
                    chunks.append(current_chunk)
                    current_chunk = sentence
                else:
                    # The sentence itself is too long, split it by 200
                    parts = [sentence[i:i+200] for i in range(0, len(sentence), 200)]
                    chunks.extend(parts)
                    current_chunk = ""
        if current_chunk:
            chunks.append(current_chunk)
            current_chunk = ""
    return chunks

# Test cases
tests = [
    ("Empty string", ""),
    ("Single word", "你好"),
    ("Single sentence with punctuation", "你好！"),
    ("Multiple sentences", "你好！今天天气怎么样？我很好。"),
    ("Paragraphs separated by blank line", "第一段落。\n\n第二段落？\n\n第三段落！"),
    ("Long sentence >200 chars", "a" * 250),
    ("Mixed Chinese English punctuation", "Hello world! 你好，世界。How are you? 你好吗？"),
    ("Complex text with multiple paragraphs and long sentences",
     "第一段落：这是一个测试。\n\n第二段落：这是一个非常长的句子，用来测试当句子长度超过200个字符时的分割行为，看看是否能正确地按照200个字符进行切分，而不会在单词或汉字中间断开。\n\n第三段落：短句。"),
]

print("Running splitIntoSpeechChunks tests...\n")
for name, text in tests:
    print(f"Test: {name}")
    print(f"Input length: {len(text)}")
    chunks = splitIntoSpeechChunks(text)
    print(f"Number of chunks: {len(chunks)}")
    for i, chunk in enumerate(chunks[:5]):  # show first 5 chunks
        print(f"  Chunk {i}: {repr(chunk[:50])}{'...' if len(chunk)>50 else ''}")
    if len(chunks) > 5:
        print(f"  ... and {len(chunks)-5} more")
    print()