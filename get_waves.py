import urllib.request
import json
url = "https://fonts.gstatic.com/s/i/materialicons/waves/v10/24px.svg"
try:
    with urllib.request.urlopen(url) as response:
        print(response.read().decode('utf-8'))
except Exception as e:
    print(e)
