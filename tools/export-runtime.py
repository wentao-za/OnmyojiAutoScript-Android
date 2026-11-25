"""Copy the complete OAS task engine into an isolated, hash-audited APK asset.

Only source/resources and selected installed libraries are copied. Personal config,
logs, account profiles, Git files and interpreter caches are never included.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path
import sys
import zipfile

parser=argparse.ArgumentParser()
parser.add_argument('--source',required=True,type=Path)
args=parser.parse_args()
source=args.source.resolve()
root=Path(__file__).resolve().parents[1]
destination=root/'app/src/main/assets/runtime'
destination.mkdir(parents=True,exist_ok=True)
allowed={'.py','.json','.png','.webp','.jpg','.jpeg','.gif','.yaml','.yml','.txt','.ttf','.otf','.onnx','.npy','.npz','.csv'}
excluded={'__pycache__','.git','log','logs','real_matches','records','captures'}
entries={}
for folder in ('module','tasks','assets','deploy'):
    for path in sorted((source/folder).rglob('*')):
        if path.is_file() and path.suffix.lower() in allowed and not excluded.intersection(path.relative_to(source).parts):
            entries[path.relative_to(source).as_posix()]=path
for filename in ('LICENSE','LICENSE.txt','script.py','config/deploy.template.yaml'):
    if (source/filename).is_file():entries[filename]=source/filename
assets_test=source/'dev_tools/assets_test.py'
# 上游 kongdr418/OnmyojiAutoScript 的 dev_tools/ 只有 settings_ui_checks.js，
# 没有 assets_test.py（该文件属于本地开发副本）。缺失时跳过，不影响运行时。
if assets_test.is_file():entries['dev_tools/assets_test.py']=assets_test
site=source/'toolkit/Lib/site-packages'
for package in ('ppocronnx','paho','onepush'):
    for path in sorted((site/package).rglob('*')):
        if path.is_file() and path.suffix.lower() in allowed and '__pycache__' not in path.parts:
            entries[path.relative_to(site).as_posix()]=path
manifest=[]
imports=set()
# These two upstream imports are unused by the actual task classes and
# otherwise initialize PC-only backends. Record each source edit below.
# 定义在循环外：收集到 0 个文件时循环不执行，仍要能写出 manifest。
removed_imports={
    'tasks/Component/SwitchAccount/switch_account.py':b'from adbutils import device',
    'tasks/Quiz/script_task.py':b'from module.device.screenshot import Screenshot',
}
with zipfile.ZipFile(destination/'oas-runtime.zip','w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for name,path in entries.items():
        data=path.read_bytes()
        if name in removed_imports:data=data.replace(removed_imports[name],b'# Android: unused desktop-only import removed')
        archive.writestr(name,data)
        manifest.append(dict(path=name,size=len(data),sha256=hashlib.sha256(data).hexdigest()))
        if path.suffix=='.py':
            for node in ast.walk(ast.parse(data.decode('utf-8-sig'))):
                if isinstance(node,ast.Import):imports.update(item.name.split('.')[0] for item in node.names)
                elif isinstance(node,ast.ImportFrom) and node.level==0 and node.module:imports.add(node.module.split('.')[0])
tasks=sorted(path.parent.name for path in (source/'tasks').glob('*/script_task.py'))
report=dict(tasks=tasks,taskCount=len(tasks),fileCount=len(manifest),unpackedBytes=sum(item['size'] for item in manifest),
            portabilityEdits={name:line.decode() for name,line in removed_imports.items()},
            archiveSha256=hashlib.sha256((destination/'oas-runtime.zip').read_bytes()).hexdigest(),
            thirdPartyImports=sorted(imports-sys.stdlib_module_names-{'module','tasks','assets','deploy','ppocronnx','oashya'}),files=manifest)
(destination/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({key:report[key] for key in ('taskCount','fileCount','unpackedBytes','thirdPartyImports')},ensure_ascii=False))
