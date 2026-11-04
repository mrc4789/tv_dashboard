"""Run with Python 3 + Tkinter on the setup computer, not on the TV."""
import os
import queue
import shutil
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk, filedialog, messagebox
from home_setup_core import HomeSetup

def find_adb():
    found=shutil.which('adb')
    if found: return found
    for base in [os.getenv('ANDROID_SDK_ROOT'),os.getenv('ANDROID_HOME'),str(Path(os.getenv('LOCALAPPDATA',''))/'Android'/'Sdk')]:
        if base:
            p=Path(base)/'platform-tools'/'adb.exe'
            if p.is_file(): return str(p)
    return ''

class App:
    def __init__(self, root):
        self.root=root; self.events=queue.Queue(); self.busy=False
        root.title('TV Dashboard – Setup Home'); root.geometry('850x650'); root.minsize(720,540)
        body=ttk.Frame(root,padding=16);body.pack(fill='both',expand=True)
        ttk.Label(body,text='Thiết lập Home cho TV / emulator',font=('Segoe UI',16,'bold')).pack(anchor='w')
        ttk.Label(body,text='Cài APK release tiêu chuẩn trước. Bật debugging trên TV và chấp nhận kết nối máy tính.').pack(anchor='w',pady=8)
        row=ttk.Frame(body);row.pack(fill='x');self.adb=tk.StringVar(value=find_adb())
        ttk.Label(row,text='adb.exe: ').pack(side='left');ttk.Entry(row,textvariable=self.adb).pack(side='left',fill='x',expand=True)
        ttk.Button(row,text='Chọn file',command=self.browse).pack(side='left')
        row=ttk.Frame(body);row.pack(fill='x',pady=8);self.address=tk.StringVar()
        ttk.Label(row,text='TV IPv4:cổng: ').pack(side='left');ttk.Entry(row,textvariable=self.address).pack(side='left',fill='x',expand=True)
        ttk.Button(row,text='Kết nối TV',command=lambda:self.perform(lambda c,s:c.connect(self.address.get()))).pack(side='left')
        ttk.Label(body,text='Dùng địa chỉ/cổng debugging do TV cung cấp. Wireless pairing bằng mã chưa được tích hợp.').pack(anchor='w')
        row=ttk.Frame(body);row.pack(fill='x',pady=8);self.devices=ttk.Combobox(row,state='readonly');self.devices.pack(side='left',fill='x',expand=True)
        ttk.Button(row,text='Làm mới thiết bị',command=self.refresh).pack(side='left')
        row=ttk.Frame(body);row.pack(fill='x',pady=8)
        for title,fn in [('Kiểm tra Home',lambda c,s:c.inspect(s)),('Đặt Dashboard làm Home',lambda c,s:c.set_home(s)),('Thử Home',lambda c,s:c.test_home(s)),('Khôi phục Home cũ',lambda c,s:c.restore(s))]:
            ttk.Button(row,text=title,command=lambda f=fn:self.perform(f,True)).pack(side='left',padx=2)
        ttk.Button(body,text='Nâng cao: tắt launcher Home cũ…',command=self.prepare_disable).pack(anchor='w',pady=8)
        ttk.Label(body,text='Không tự tắt launcher hãng. Nút nâng cao cần xác nhận, có thể làm mất giao diện Home và tính năng của hãng.',wraplength=790).pack(anchor='w')
        self.status=tk.StringVar(value='Sẵn sàng');ttk.Label(body,textvariable=self.status).pack(anchor='w',pady=6)
        self.log=tk.Text(body,wrap='word',height=14,state='disabled');self.log.pack(fill='both',expand=True)
        ttk.Label(body,text='Khôi phục lưu tại thư mục người dùng: .tvdashboard-home-backups. Không xóa trước khi nghiệm thu.').pack(anchor='w',pady=6)
        root.after(100,self.poll)
    def browse(self):
        if self.busy:return
        p=filedialog.askopenfilename(title='Chọn adb.exe trong Android SDK/platform-tools')
        if p:self.adb.set(p)
    def perform(self,fn,need_device=False,done=None):
        if self.busy:return
        serial=self.devices.get().split('  ')[0]
        if not self.adb.get().strip():messagebox.showerror('Thiếu ADB','Chọn adb.exe trước.');return
        if need_device and not serial:messagebox.showerror('Chưa chọn TV','Làm mới và chọn đúng thiết bị.');return
        core=HomeSetup(self.adb.get().strip(),Path.home()/'.tvdashboard-home-backups')
        # Read Tk values on the UI thread before executing any worker operation.
        if not need_device and done is None:
            addr=self.address.get()
            fn=lambda c,s:c.connect(addr)
        self.busy=True;self.status.set('Đang xử lý…')
        def worker():
            try:self.events.put((True,fn(core,serial),done))
            except Exception as e:self.events.put((False,str(e),None))
        threading.Thread(target=worker,daemon=True).start()
    def refresh(self):
        def done(rows):
            self.devices['values']=[f'{s}  [{state}]' for s,state in rows]
            if rows:self.devices.current(0)
            else:self.devices.set('')
        self.perform(lambda c,s:c.devices(),done=done)
    def prepare_disable(self):
        def confirm(state):
            if messagebox.askyesno('Xác nhận thao tác nâng cao',
                f"Thiết bị: {state['serial']}\nUser: {state['user']}\nHome sẽ bị tắt: {state['home']}\n\nChỉ dùng khi chọn Home thông thường thất bại. Launcher và một số chức năng hãng có thể không dùng được. Giữ kết nối debugging để khôi phục. Công cụ lưu bản khôi phục trước khi thay đổi.\n\nBạn có muốn tiếp tục?"):
                self.perform(lambda c,s:c.disable_old(state),True)
        self.perform(lambda c,s:c.inspect(s),True,done=confirm)
    def poll(self):
        try:
            while True:
                ok,value,done=self.events.get_nowait();self.busy=False
                self.status.set('Hoàn tất yêu cầu — xem kết quả bên dưới' if ok else 'Không hoàn tất')
                self.log.configure(state='normal');self.log.insert('end',str(value)+'\n\n');self.log.see('end');self.log.configure(state='disabled')
                if ok and done:done(value)
        except queue.Empty:pass
        self.root.after(100,self.poll)

if __name__=='__main__':
    window=tk.Tk();App(window);window.mainloop()
