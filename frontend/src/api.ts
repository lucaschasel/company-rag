let csrf: {token:string;headerName:string}|undefined;
export async function api<T>(path:string,method='GET',body?:unknown):Promise<T> {
  const headers:Record<string,string>={};
  if(method!=='GET') {
    if(!csrf) {const res=await fetch('/api/auth/csrf');if(!res.ok)throw new Error('无法连接服务');csrf=await res.json();}
    headers[csrf!.headerName]=csrf!.token;
  }
  if(body && !(body instanceof FormData))headers['Content-Type']='application/json';
  const res=await fetch('/api'+path,{method,headers,body:body instanceof FormData?body:body?JSON.stringify(body):undefined});
  if(!res.ok) {
    if(res.status===401 && path!=='/auth/login')window.dispatchEvent(new Event('session-expired'));
    const err=await res.json().catch(()=>({}));throw new Error(err.message||({401:'请先登录',403:'权限不足或会话已过期，请重新登录',413:'文件不能超过 20 MB'}[res.status])||'操作失败，请稍后重试');
  }
  const text=await res.text();return (text?JSON.parse(text):undefined) as T;
}
export function resetCsrf(){csrf=undefined;}
// 流式请求：返回原始 Response，由调用方读取 SSE/流式 body。
export async function apiStream(path:string,body?:unknown):Promise<Response> {
  const headers:Record<string,string>={};
  if(!csrf) {const res=await fetch('/api/auth/csrf');if(!res.ok)throw new Error('无法连接服务');csrf=await res.json();}
  headers[csrf!.headerName]=csrf!.token;
  if(body)headers['Content-Type']='application/json';
  const res=await fetch('/api'+path,{method:'POST',headers,body:body?JSON.stringify(body):undefined});
  if(!res.ok) {
    if(res.status===401)window.dispatchEvent(new Event('session-expired'));
    const err=await res.json().catch(()=>({}));throw new Error(err.message||'操作失败，请稍后重试');
  }
  return res;
}
