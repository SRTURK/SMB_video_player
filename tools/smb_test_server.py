import sys, logging, os
from pathlib import Path
from impacket.smbserver import SimpleSMBServer
if os.name=='nt':
    original_open=os.open
    def binary_open(path,flags,*args,**kwargs):
        return original_open(path,flags|os.O_BINARY,*args,**kwargs)
    os.open=binary_open
logging.disable(logging.CRITICAL)
server=SimpleSMBServer(listenAddress='127.0.0.1',listenPort=1445)
server.addShare('fixture',str(Path(__file__).parent/'fixtures'),readOnly='yes')
if os.environ.get('SMB_TEST_ACTUAL_DIR'):
    server.addShare('actual',os.environ['SMB_TEST_ACTUAL_DIR'],readOnly='yes')
server.setSMB2Support(True)
server.start()
