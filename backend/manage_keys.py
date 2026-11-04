"""Local key lifecycle: list/create/revoke. DB owner only; no public enrollment endpoint."""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import sqlite3
ROOT=Path(__file__).resolve().parent

def main():
    p=argparse.ArgumentParser();p.add_argument('--db',default=str(ROOT/'data/config.sqlite3'))
    sub=p.add_subparsers(dest='action',required=True);sub.add_parser('list')
    create=sub.add_parser('create');create.add_argument('id');create.add_argument('--departments',nargs='+')
    revoke=sub.add_parser('revoke');revoke.add_argument('id');args=p.parse_args()
    if not Path(args.db).is_file():p.error('Chạy Start-Service.cmd trước để tạo DB.')
    with sqlite3.connect(args.db) as db:
        if args.action=='list':
            for row in db.execute('SELECT key_id,scope_json,active FROM api_keys'):print(json.dumps(dict(zip(['id','departments','active'],row))))
        elif args.action=='revoke':
            if db.execute('UPDATE api_keys SET active=0 WHERE key_id=?',(args.id,)).rowcount!=1:p.error('Key ID không tồn tại.')
            print('Đã thu hồi key. Key giữ trạng thái thu hồi sau restart.')
        else:
            if not args.id or len(args.id)>64:p.error('Key ID cần 1–64 ký tự.')
            if db.execute('SELECT 1 FROM api_keys WHERE key_id=?',(args.id,)).fetchone():p.error('ID đã tồn tại. Dùng ID mới khi xoay key.')
            if args.departments:
                for dep in args.departments:
                    if not db.execute('SELECT 1 FROM departments WHERE id=? AND active=1',(dep,)).fetchone():p.error('Phòng ban không tồn tại: '+dep)
            value=secrets.token_urlsafe(32)
            db.execute('INSERT INTO api_keys VALUES(?,?,?,1)',(args.id,hashlib.sha256(value.encode()).hexdigest(),json.dumps(args.departments) if args.departments else None))
            print('Key chỉ hiển thị lần này; lưu vào nơi quản lý secret của bạn:')
            print(value)

if __name__=='__main__':main()
