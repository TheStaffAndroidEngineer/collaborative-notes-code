"""Independent protocol histories; isolated process, temporary database, ephemeral HTTP."""
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from base import Problem, handler
from service import Service

class Review(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.database=Path(self.temp.name)/'state.sqlite'
  self.a=Service(self.database);self.b=Service(self.database);self.servers=[]
 def tearDown(self):
  for server,thread in self.servers:server.shutdown();server.server_close();thread.join(2)
  self.temp.cleanup()
 def body(self,action,args=None,identity=None):return {'id':identity or str(uuid.uuid4()),'action':action,'args':args or {}}
 def run_command(self,body,owner=None):return (owner or self.a).route('POST','/commands',body)[1]
 def command(self,action,args=None,owner=None):return self.run_command(self.body(action,args),owner)
 def view(self,owner=None):return (owner or self.a).route('GET','/view',{})[1]['data']
 def serve(self):
  server=ThreadingHTTPServer(('127.0.0.1',0),handler(self.a));thread=threading.Thread(target=lambda:server.serve_forever(poll_interval=.01),daemon=True);thread.start();self.servers.append((server,thread));return server.server_port
 def http(self,port,path,body=None):
  c=http.client.HTTPConnection('127.0.0.1',port,timeout=4)
  try:
   c.request('GET' if body is None else 'POST',path,body=None if body is None else json.dumps(body));r=c.getresponse();return r.status,json.loads(r.read())
  finally:c.close()

 def row(self,key='p-arrival'):return next(r for r in self.view()['paragraphs'] if r['id']==key)
 def edit(self,text,revision=1,key='p-arrival'):return {'paragraphId':key,'baseRevision':revision,'text':text}
 def test_three_way_resolution_completes_without_silent_overwrite(self):
  base=self.row();self.command('remote',self.edit('Meet at the garden studio at 09:00.'))
  stale=self.body('save',self.edit('Meet at the north studio at 09:30.'));conflict=self.run_command(stale)
  self.assertFalse(conflict['outcome']['accepted']);self.assertEqual(2,conflict['outcome']['conflict']['revision'])
  self.assertEqual('Meet at the north studio at 09:00.',base['text'])
  self.command('save',self.edit('Meet at the garden studio at 09:30.',2))
  self.assertEqual((3,'Meet at the garden studio at 09:30.'),(self.row()['revision'],self.row()['text']))
  self.assertEqual(conflict,self.run_command(stale,Service(self.database)))
  self.assertEqual(3,self.row()['revision'])
 def test_same_paragraph_two_owners_admit_only_one_revision(self):
  with concurrent.futures.ThreadPoolExecutor(2) as pool:
   outcomes=list(pool.map(lambda pair:self.command('save',self.edit(pair[1]),pair[0])['outcome']['accepted'],[(self.a,'A'),(self.b,'B')]))
  self.assertEqual([False,True],sorted(outcomes));self.assertEqual(2,self.row()['revision'])
 def test_different_paragraphs_progress_independently(self):
  with concurrent.futures.ThreadPoolExecutor(2) as pool:
   outcomes=list(pool.map(lambda pair:self.command('save',self.edit(pair[1],key=pair[1]),pair[0])['outcome']['accepted'],[(self.a,'p-arrival'),(self.b,'p-supplies')]))
  self.assertEqual([True,True],outcomes);self.assertEqual(1,self.row('p-access')['revision'])
 def test_deleted_paragraph_cannot_be_recreated_by_new_or_old_operations(self):
  body=self.body('save',self.edit('Accepted once'));receipt=self.run_command(body)
  self.command('delete',{'paragraphId':'p-arrival','baseRevision':2})
  self.assertEqual(receipt,self.run_command(body,self.b))
  for revision in (1,2,3):self.assertFalse(self.command('save',self.edit('Resurrect',revision))['outcome']['accepted'])
  self.assertTrue(self.row()['deleted']);self.assertEqual(3,self.row()['revision'])
 def test_delete_racing_save_has_one_cas_winner(self):
  with concurrent.futures.ThreadPoolExecutor(2) as pool:
   outcomes=list(pool.map(lambda body:self.run_command(body)['outcome']['accepted'],[self.body('save',self.edit('Edit')),self.body('delete',{'paragraphId':'p-arrival','baseRevision':1})]))
  self.assertEqual([False,True],sorted(outcomes));self.assertEqual(2,self.row()['revision'])
 def test_lost_save_recovery_has_one_revision_and_later_current_view(self):
  port=self.serve();body=self.body('save',self.edit('Local accepted'))
  self.http(port,'/__control',{'drop_reply':True})
  with self.assertRaises(http.client.RemoteDisconnected):self.http(port,'/commands',body)
  self.http(port,'/__control',{});reconstructed=Service(self.database)
  receipt=self.run_command(body,reconstructed);self.assertEqual(2,receipt['outcome']['revision'])
  self.command('remote',self.edit('New current',2));self.assertEqual(receipt,self.run_command(body,reconstructed))
  self.assertEqual((3,'New current'),(self.row()['revision'],self.row()['text']))
 def test_replayed_conflict_does_not_change_after_text_returns_to_base(self):
  original=self.row()['text'];self.command('remote',self.edit('Other text'))
  body=self.body('save',self.edit('My text'));old=self.run_command(body)
  self.command('remote',self.edit(original,2));self.assertEqual(old,self.run_command(body,self.b))
  self.assertFalse(old['outcome']['accepted']);self.assertEqual(3,self.row()['revision'])
 def test_payload_conflict_and_bad_revision_roll_back(self):
  body=self.body('save',self.edit('A'));self.run_command(body)
  with self.assertRaises(Problem):self.run_command(body|{'args':self.edit('B')})
  before=self.view()
  for value in (True,1.0,0,'1'):
   with self.assertRaises(Problem):self.command('save',self.edit('Invalid',value))
  self.assertEqual(before,self.view())
 def test_unknown_identity_rejection_is_durable_without_adding_paragraph(self):
  body=self.body('save',self.edit('Unknown',key='absent'));first=self.run_command(body)
  self.assertFalse(first['outcome']['accepted']);self.assertEqual(first,self.run_command(body,self.b))
  self.assertEqual(3,len(self.view()['paragraphs']))

if __name__=='__main__':unittest.main()
