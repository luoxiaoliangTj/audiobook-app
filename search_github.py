import re
import requests
import json

with open('/data/data/com.termux/files/home/.git-credentials', 'r') as f:
    content = f.read()

match = re.search(r'https://luoxiaoliangTj:([^@]+)@github\.com', content)
if not match:
    print("Token not found")
    exit(1)

token = match.group(1)
headers = {'Authorization': f'token {token}', 'Accept': 'application/vnd.github.v3+json'}

queries = [
    "audiobook reader android kotlin stars:>100",
    "epub reader android kotlin stars:>100", 
    "tts audiobook android kotlin",
    "FolioReader Android",
    "ReadEra Android"
]

for query in queries:
    resp = requests.get(
        'https://api.github.com/search/repositories',
        headers=headers,
        params={'q': query, 'sort': 'stars', 'per_page': 5}
    )
    if resp.status_code == 200:
        data = resp.json()
        print(f"\n=== {query} (total: {data['total_count']}) ===")
        for repo in data['items'][:3]:
            print(f"  {repo['full_name']} - ⭐{repo['stargazers_count']} - {repo['description'][:100] if repo['description'] else 'No description'}")
            print(f"    URL: {repo['html_url']}")
    else:
        print(f"\n=== {query} === ERROR: {resp.status_code}")
