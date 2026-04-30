import subprocess
import sys

def run_command(command):
    process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout, stderr = process.communicate()
    if process.returncode != 0:
        print(f"Error executing command: {command}")
        print(stderr.decode('utf-8'))
    else:
        print(stdout.decode('utf-8'))

run_command("git push -u origin feature/streaming-ai-responses-2")
