"""Import the attributed public corpus through authenticated application APIs."""
import http.cookiejar, json, pathlib, time, urllib.request, uuid, sys

ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE='http://localhost:8080/api'
opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf={}
def api(path,method='GET',body=None,content_type='application/json'):
    headers={}
    if method!='GET': headers[csrf['headerName']]=csrf['token']
    if body is not None:
        headers['Content-Type']=content_type
        if not isinstance(body,bytes):body=json.dumps(body,ensure_ascii=False).encode('utf-8')
    with opener.open(urllib.request.Request(BASE+path,data=body,headers=headers,method=method),timeout=150) as response:
        data=response.read()
        return json.loads(data) if data else None
def main():
    global csrf
    csrf=api('/auth/csrf')
    password=(ROOT/'.tools/dev-password.txt').read_text(encoding='utf-8-sig').strip()
    api('/auth/login','POST',{'username':'admin','password':password})
    manifest=json.loads((ROOT/'corpus/kubernetes/manifest.json').read_text(encoding='utf-8'))
    existing={k['name']:k for k in api('/kbs')}
    imported=[]
    for doc in manifest['documents']:
        name='Kubernetes · '+doc['category']
        if name not in existing:
            existing[name]=api('/kbs','POST',{'name':name,'description':'Kubernetes 官方中文文档，CC BY 4.0；来源、版本及整理说明见文档首页。'})
        kid=existing[name]['id']
        docs=api(f'/kbs/{kid}/documents')
        if '--replace' in sys.argv:
            for old in docs:
                if old['name']==doc['file']:
                    with opener.open(BASE+f'/kbs/{kid}/documents/{old["id"]}/download') as response: old_text=response.read().decode('utf-8')
                    if doc['revision'] not in old_text:raise RuntimeError('已有文件不是本次导入版本，拒绝替换')
                    api(f'/kbs/{kid}/documents/{old["id"]}','DELETE')
            docs=api(f'/kbs/{kid}/documents')
        if not any(d['name']==doc['file'] for d in docs):
            boundary='KnowledgeHub'+uuid.uuid4().hex
            file=(ROOT/'corpus/kubernetes'/doc['file']).read_bytes()
            body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{doc["file"]}"\r\nContent-Type: text/markdown\r\n\r\n'.encode('utf-8')+file+f'\r\n--{boundary}--\r\n'.encode())
            api(f'/kbs/{kid}/documents','POST',body,'multipart/form-data; boundary='+boundary)
        imported.append((kid,doc['file']))
    deadline=time.monotonic()+180
    while True:
        docs=[d for kid in {i[0] for i in imported} for d in api(f'/kbs/{kid}/documents')]
        relevant=[d for d in docs if d['name'] in {i[1] for i in imported}]
        if all(d['status'] in ('READY','FAILED') for d in relevant):break
        if time.monotonic()>deadline:raise RuntimeError('入库尚未完成，请在页面查看状态')
        time.sleep(2)
    if any(d['status']!='READY' for d in relevant):raise RuntimeError('有文档处理失败，请在页面查看原因')
    checks=[]
    for category,question in [('基础概念','Pod 是什么？'),('工作负载管理','Deployment 如何回滚？'),('配置与安全','ConfigMap 和 Secret 有什么区别？'),('网络与服务','Service 如何发现 Pod？')]:
        hits=api('/search','POST',{'kbId':existing['Kubernetes · '+category]['id'],'question':question,'topK':3})
        if not hits:raise RuntimeError('检索未命中：'+question)
        checks.append({'question':question,'sources':[h['documentName'] for h in hits]})
    report={'knowledgeBases':len({i[0] for i in imported}),'documents':len(relevant),'chunks':sum(d['chunk_count'] for d in relevant),'status':'READY','checks':checks}
    (ROOT/'docs/corpus-import-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))
    api('/auth/logout','POST')
if __name__=='__main__':main()
