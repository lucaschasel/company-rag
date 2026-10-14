"""Download a pinned, attributed Kubernetes Chinese documentation corpus."""
import concurrent.futures, datetime, hashlib, json, pathlib, re, urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1] / 'corpus' / 'kubernetes'
FILES = [
 ('Kubernetes概述', 'overview/_index.md', '基础概念'),
 ('Pod工作负载', 'workloads/pods/_index.md', '基础概念'),
 ('Deployment部署', 'workloads/controllers/deployment.md', '工作负载管理'),
 ('ConfigMap配置', 'configuration/configmap.md', '配置与安全'),
 ('Secret敏感配置', 'configuration/secret.md', '配置与安全'),
 ('Service服务发现', 'services-networking/service.md', '网络与服务'),
]
def fetch(url):
    req=urllib.request.Request(url,headers={'User-Agent':'KnowledgeHub-corpus-import'})
    with urllib.request.urlopen(req,timeout=60) as response: return response.read()
def clean(raw):
    text=re.sub(r'^---\s*\n.*?\n---\s*\n','',raw,count=1,flags=re.S)
    text=re.sub(r'<!--.*?-->','',text,flags=re.S)
    def shortcode(match):
        tag=match.group(0)
        label=re.search(r'\btext="([^"]+)"',tag)
        if label:return label.group(1)
        sample=re.search(r'\bfile="([^"]+)"',tag)
        if sample:return '\n[引用示例文件：'+sample.group(1)+'，请查阅官方原文]\n'
        term=re.search(r'glossary_definition\s+term_id="([^"]+)"',tag)
        if term:return '[术语定义：'+term.group(1)+'，参见官方原文]\n'
        return ''
    text=re.sub(r'\{\{[<%].*?[%>]\}\}',shortcode,text,flags=re.S)
    return re.sub(r'\n{3,}','\n\n',text)
def main():
    ROOT.mkdir(parents=True,exist_ok=True)
    (ROOT/'original').mkdir(exist_ok=True)
    revision=json.loads(fetch('https://api.github.com/repos/kubernetes/website/commits/main'))['sha']
    license_bytes=fetch(f'https://raw.githubusercontent.com/kubernetes/website/{revision}/LICENSE')
    (ROOT/'LICENSE').write_bytes(license_bytes)
    date=datetime.datetime.now(datetime.timezone(datetime.timedelta(hours=8))).isoformat()
    def download(item):
        title,path,category=item
        source=f'https://raw.githubusercontent.com/kubernetes/website/{revision}/content/zh-cn/docs/concepts/{path}'
        original=fetch(source)
        raw=original.decode('utf-8')
        (ROOT/'original'/f'{title}.md').write_bytes(original)
        text=clean(raw)
        page='https://kubernetes.io/zh-cn/docs/concepts/'+path.removesuffix('_index.md').removesuffix('.md').rstrip('/')+'/'
        header=f'# {title}\n\n来源：{page}\n作者：Kubernetes Authors / Kubernetes website contributors\n许可：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）\n获取时间：{date}\n原始版本：{revision}\n整理说明：删除 Hugo 元数据、模板标记及 HTML 注释中的英文对照；正文未由 AI 改写。模板引用示例请查阅官方原文。\n\n---\n\n'
        (ROOT/f'{title}.md').write_text(header+text,encoding='utf-8')
        return dict(title=title,file=f'{title}.md',category=category,url=page,source=source,revision=revision,sha256=hashlib.sha256(original).hexdigest())
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: docs=list(pool.map(download,FILES))
    (ROOT/'manifest.json').write_text(json.dumps({'retrievedAt':date,'license':'CC BY 4.0','documents':docs},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'documents':len(docs),'revision':revision},ensure_ascii=False))
if __name__=='__main__': main()
