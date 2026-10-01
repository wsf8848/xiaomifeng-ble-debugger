#!/usr/bin/env python3
"""
小蜜蜂调试助手Pro — 服务器自动部署脚本
上传服务器文件 + SSH 执行部署 + 导入授权码
"""
import os, sys, time
import paramiko

SERVER_IP = os.getenv("XMF_SERVER_IP", "")
SSH_USER = os.getenv("XMF_SSH_USER", "ubuntu")
SSH_PASSWORD = os.getenv("XMF_SSH_PASSWORD", "")
SERVER_DIR = os.getenv("XMF_SERVER_DIR", "/opt/liesun-server")
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

def run_ssh(ssh, cmd, timeout=30):
    print(f"  $ {cmd}")
    stdin, stdout, stderr = ssh.exec_command(cmd, timeout=timeout)
    exit_code = stdout.channel.recv_exit_status()
    out = stdout.read().decode().strip()
    err = stderr.read().decode().strip()
    if out: print(f"    {out}")
    if err: print(f"    (stderr) {err}")
    return exit_code, out, err

def deploy():
    missing = [name for name, value in {
        "XMF_SERVER_IP": SERVER_IP,
        "XMF_SSH_USER": SSH_USER,
        "XMF_SSH_PASSWORD": SSH_PASSWORD,
    }.items() if not value]
    if missing:
        raise RuntimeError("缺少部署环境变量: " + ", ".join(missing))

    print("=" * 55)
    print("  小蜜蜂调试助手Pro — 服务器自动部署")
    print("=" * 55)

    # 连接 SSH
    print("\n🔌 连接服务器...")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(SERVER_IP, username=SSH_USER, password=SSH_PASSWORD, timeout=15)
        print("   ✅ SSH 连接成功")
    except Exception as e:
        print(f"   ❌ SSH 连接失败: {e}")
        return False

    try:
        # Step 1: 创建目录
        print("\n📁 创建目录...")
        run_ssh(ssh, f"sudo mkdir -p {SERVER_DIR}/apks {SERVER_DIR}/logs")
        run_ssh(ssh, f"sudo chown -R {SSH_USER}:{SSH_USER} {SERVER_DIR}")

        # Step 2: 上传文件
        print("\n📤 上传服务器文件...")
        sftp = ssh.open_sftp()
        for fname in ["app.py", "requirements.txt", "import_licenses.py"]:
            local_path = os.path.join(SCRIPT_DIR, fname)
            remote_path = f"{SERVER_DIR}/{fname}"
            sftp.put(local_path, remote_path)
            print(f"   ✅ {fname}")
        sftp.close()

        # Step 3: 安装依赖
        print("\n📦 安装系统依赖...")
        run_ssh(ssh, "sudo apt update -qq && sudo apt install -y -qq python3-pip python3-venv sqlite3", timeout=120)
        
        print("\n📦 创建 venv 并安装 Python 依赖...")
        run_ssh(ssh, f"cd {SERVER_DIR} && python3 -m venv venv", timeout=30)
        run_ssh(ssh, f"cd {SERVER_DIR} && source venv/bin/activate && pip install -q flask gunicorn", timeout=120)
        
        # Step 3.5: 安装 pycryptodome（用于解密 Gitee 授权码）
        run_ssh(ssh, f"cd {SERVER_DIR} && source venv/bin/activate && pip install -q pycryptodome", timeout=60)

        # Step 4: 导入授权码
        print("\n📋 导入授权码...")
        run_ssh(ssh, f"cd {SERVER_DIR} && source venv/bin/activate && python3 import_licenses.py", timeout=30)

        # Step 5: 创建 systemd 服务
        print("\n⚙️ 创建 systemd 服务...")
        systemd_content = '''[Unit]
Description=小蜜蜂调试助手Pro 授权 & OTA 服务器
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/liesun-server
ExecStart=/opt/liesun-server/venv/bin/gunicorn -w 2 -b 0.0.0.0:5000 app:app
Restart=always
RestartSec=5
StandardOutput=append:/opt/liesun-server/logs/access.log
StandardError=append:/opt/liesun-server/logs/error.log

[Install]
WantedBy=multi-user.target
'''
        # 写入临时文件并通过 sftp 上传
        tmp_local = "/tmp/liesun-server.service"
        with open(tmp_local, "w") as f:
            f.write(systemd_content)
        sftp = ssh.open_sftp()
        sftp.put(tmp_local, f"{SERVER_DIR}/liesun-server.service")
        sftp.close()
        os.remove(tmp_local)

        run_ssh(ssh, f"sudo cp {SERVER_DIR}/liesun-server.service /etc/systemd/system/liesun-server.service")
        run_ssh(ssh, "sudo systemctl daemon-reload")
        run_ssh(ssh, "sudo systemctl enable liesun-server")
        run_ssh(ssh, "sudo systemctl start liesun-server")
        time.sleep(2)

        # Step 6: 检查服务状态
        print("\n🔍 检查服务状态...")
        rc, out, _ = run_ssh(ssh, "sudo systemctl is-active liesun-server")
        is_active = "active" in out

        # Step 7: 开放端口
        run_ssh(ssh, "sudo iptables -I INPUT -p tcp --dport 5000 -j ACCEPT 2>/dev/null || true")

        # Step 8: 测试 API
        print("\n🧪 测试 API...")
        import urllib.request
        try:
            resp = urllib.request.urlopen(f"http://{SERVER_IP}:5000/api/health", timeout=10)
            health = json.loads(resp.read().decode())
            print(f"   ✅ /api/health → {health}")
        except Exception as e:
            print(f"   ⚠️ 健康检查: {e}")

        print("\n" + "=" * 55)
        if is_active:
            print("  ✅ 服务器部署成功！")
            print(f"  地址: http://{SERVER_IP}:5000")
        else:
            print("  ⚠️ 服务可能未完全启动，请检查日志")
        print("=" * 55)
        return True

    except Exception as e:
        print(f"\n   ❌ 部署失败: {e}")
        return False
    finally:
        ssh.close()

if __name__ == "__main__":
    import json
    deploy()
