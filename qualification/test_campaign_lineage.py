"""Temporary-filesystem histories exercise native classifiers and fail-closed custody."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + '.py'))
    loaded = importlib.util.module_from_spec(spec); spec.loader.exec_module(loaded)
    return loaded

lineage = module('campaign_lineage')
native = module('mutation_campaign')


class LineageTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.out = self.root / 'qualification/results'; self.out.mkdir(parents=True, mode=0o700); self.out.parent.chmod(0o700)
        self.candidates = [{'fingerprint': hashlib.sha256(str(i).encode()).hexdigest(),
                            'path': 'src/cljs/axxium/example.cljs', 'char_start': i,
                            'char_end': i+1, 'byte_offset': i, 'source_sha256': 'a'*64,
                            'before': '1', 'after': '0', 'kind': 'integer'} for i in range(4)]
        self.campaign = SimpleNamespace(ROOT=self.root, SOURCE_SHA='b'*40,
            inventory=lambda **kw: copy.deepcopy(self.candidates), batch_for=native.batch_for,
            static_plan_for_path=lambda path: {'key':'cljs', 'compile_targets':['test'], 'groups':['cljs']},
            classify_compile=native.classify_compile, classify_test=native.classify_test,
            counts=native.counts, driver_summary=native.driver_summary)
        self.candidates, self.selection = lineage.select_candidates(self.campaign, profile='standard-v2',
            path_prefix='src/cljs/', batch_index=None, sample_seed=None)
        self.producer = {'revision':'c'*40, 'files':{str(i)+'.py':'d'*64 for i in range(6)}}
        self.harness = {'harness_sha':'e'*40, 'files':{'test.cljs':'f'*64}}
        self.summary = {'schema':'axxium-qualification-test-phase/v1', 'plan':'cljs',
            'tests':2,'assertions':4,'failures':0,'errors':0,'warnings':[], 'timed_out':False,
            'containment_unknown':False,'groups':[{'name':'cljs','tests':2,'assertions':4,
                'failures':0,'errors':0,'warnings':[],'timed_out':False}]}
        self.build = 'Build completed. (0 warnings)\n'
        self.test = self.output(self.summary)

    def output(self, summary):
        return 'QUALIFICATION_TEST_SUMMARY=' + json.dumps(summary) + '\n'

    def write(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        if isinstance(value, dict): value = json.dumps(value)
        path.write_text(value); path.chmod(0o600)
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def baseline(self, directory, prefix='baseline'):
        return {'tests':2,'assertions':4,'failures':0,'errors':0,'driver_plan':'cljs','driver_groups':['cljs'],
            'driver_summary':copy.deepcopy(self.summary),
            'compile_log_sha256':self.write(directory/(prefix+'.cljs.compile.log'),self.build),
            'test_log_sha256':self.write(directory/(prefix+'.cljs.test.log'),self.test)}

    def report(self, name, start=0, statuses=('survived',), continuation=None):
        directory = self.out/name
        baseline = self.baseline(directory)
        rows=[]
        for i,status in enumerate(statuses):
            row = {**self.candidates[start+i], 'test_plan':'cljs', 'compile_exit':0,
                'compile_timed_out':False,'compile_output_overflow':False,'compile_containment_unknown':False,
                'compile_status':'passed','compile_log_sha256':self.write(directory/f'{i:04}.compile.log',self.build),
                'phase':'test','status':status,'test_exit':0,'test_timed_out':False,
                'test_output_overflow':False,'test_containment_unknown':False}
            summary=copy.deepcopy(self.summary)
            if status=='killed':
                summary['failures']=summary['groups'][0]['failures']=1; row['test_exit']=1
            if status=='test_error':
                summary['errors']=summary['groups'][0]['errors']=1; row['test_exit']=1
            if status=='timeout':
                row.update(test_timed_out=True,test_containment_unknown=True,test_exit=-9)
            output=self.output(summary)
            row.update(test_log_sha256=self.write(directory/f'{i:04}.test.log',output),
                       counts=self.campaign.counts(output),driver_summary=summary)
            rows.append(row)
        data={'source_sha':self.campaign.SOURCE_SHA,'application_under_test':self.campaign.SOURCE_SHA,
            'selection':copy.deepcopy(self.selection),'producer':copy.deepcopy(self.producer),
            'test_harness':copy.deepcopy(self.harness),'operator_id':'worker-one', 'batch_index':None,
            'sample_seed':None,'observed_prefix_start':start,'candidate_inventory':4,'execution_inventory':4-start,
            'completed_selection':False,'qualified_batch':False,'baselines':{'cljs':baseline},
            'test_plans':{'cljs':{'key':'cljs','compile_targets':['test'],'groups':['cljs']}},
            'candidate_test_plans':{c['fingerprint']:'cljs' for c in self.candidates},'results':rows,
            'containment':'unknown_after_aborted_execution' if statuses and statuses[-1]=='timeout' else 'not_proven'}
        end = start + len(rows)
        aborted = bool(statuses and statuses[-1] == 'timeout')
        data.update(observed_prefix_end=end,
                    unexecuted_fingerprints=[row['fingerprint'] for row in self.candidates[end:]],
                    terminal_state='aborted' if aborted else 'clean_partial')
        if not aborted:
            data['completed_selection'] = end == len(self.candidates)
            data['terminal_state'] = 'complete' if data['completed_selection'] else 'clean_partial'
            data['restored_baseline'] = 'passed'
            data['restored_baselines'] = {'cljs': self.baseline(directory, 'restored')}
        if continuation is not None:data['continuation']=continuation
        path=directory/'report.json';self.write(path,data)
        return path,data

    def load(self,path):
        return lineage.load_history(self.campaign,path,candidates=self.candidates,selection=self.selection,
                                    producer=self.producer,harness=self.harness)

    def ack(self, history, name='containment-ack-200.json'):
        rows=history['document']['results'];i=len(rows)-1
        data={'marker':{'source_sha':self.campaign.SOURCE_SHA,'state':'execution_active_or_containment_unknown',
                       'operator_pid':123,'started_ns':100,
                       'logfile':str(Path(history['path']).parent/f'{i:04}.test.log')},
              'inspection_note':'Native cgroup inspected empty; source restored.', 'inspection_author':'inspector-two',
              'acknowledger_pid':456,'acknowledged_ns':200,'attestation':'operator assertion, not independently verified'}
        path=self.out/name;self.write(path,data)
        return path,data,lineage.validate_ack(self.campaign,path,history)

    def restoration(self,history,ack,name='restore-one'):
        directory=self.out/name
        data={'schema':'axxium-campaign-restoration/v1','source_sha':self.campaign.SOURCE_SHA,
            'selection':copy.deepcopy(self.selection),'producer':copy.deepcopy(self.producer),
            'test_harness':copy.deepcopy(self.harness),
            'prior_report':{k:history[k] for k in ('path','sha256','next_index')},
            'containment_ack':{k:ack[k] for k in ('path','sha256')},
            'status':'passed','warnings':[],'timed_out':False,'started_ns':201,'completed_ns':300,
            'baselines':{'cljs':self.baseline(directory,'restore')}}
        path=directory/'restoration.json';self.write(path,data)
        reference=lineage.validate_restoration(self.campaign,path,history=history,ack=ack,
            candidates=self.candidates,selection=self.selection,producer=self.producer,harness=self.harness)
        return path,data,reference

    def test_selection_freezes_whole_inventory_and_original_seed_partition_order(self):
        all_rows=copy.deepcopy(self.candidates)
        with mock.patch.object(self.campaign,'inventory',return_value=all_rows) as inventory:
            rows,selection=lineage.select_candidates(self.campaign,profile='legacy-v1',path_prefix='src/cljs/',
                                                     batch_index=1,sample_seed='original-seed')
        inventory.assert_called_once_with(profile='legacy-v1')
        expected=[c for c in all_rows if native.batch_for(c['fingerprint'])==1]
        expected.sort(key=lambda c:hashlib.sha256(('original-seed:'+c['fingerprint']).encode()).hexdigest())
        self.assertEqual(rows,expected)
        self.assertEqual(selection['inventory_fingerprints'],sorted(c['fingerprint'] for c in all_rows))
        self.assertEqual(selection['selection_sha256'],hashlib.sha256(lineage._canonical([c['fingerprint'] for c in rows])).hexdigest())

    def test_native_negative_and_positive_rows_preserve_complete_prefix(self):
        path,_=self.report('valid',statuses=('survived','killed','test_error','timeout'))
        history=self.load(path)
        self.assertEqual(history['next_index'],4)
        self.assertEqual([r['status'] for r in history['document']['results']],['survived','killed','test_error','timeout'])

    def test_observed_end_unexecuted_tail_and_terminal_history_are_mandatory(self):
        path, data = self.report('full-tail', statuses=('survived', 'timeout'))
        self.assertEqual(self.load(path)['next_index'], 2)
        changes = [('observed_prefix_end', 1), ('observed_prefix_end', True),
                   ('unexecuted_fingerprints', []),
                   ('unexecuted_fingerprints', list(reversed(data['unexecuted_fingerprints']))),
                   ('terminal_state', 'running'), ('terminal_state', 'complete'),
                   ('terminal_state', 'clean_partial'), ('containment', 'not_proven')]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                altered = copy.deepcopy(data); altered[key] = value; self.write(path, altered)
                with self.assertRaises(ValueError): self.load(path)
        for key in ('observed_prefix_end', 'unexecuted_fingerprints', 'terminal_state'):
            altered = copy.deepcopy(data); del altered[key]; self.write(path, altered)
            with self.subTest(missing=key), self.assertRaises(ValueError): self.load(path)
        path, data = self.report('clean-must-be-restored')
        del data['restored_baseline']; del data['restored_baselines']; self.write(path, data)
        with self.assertRaises(ValueError): self.load(path)

    def test_candidate_order_source_profile_producer_harness_and_inventory_changes_refuse(self):
        path,data=self.report('mismatch')
        changes=[('source_sha','0'*40),('producer',{}),('test_harness',{}),('selection',{}),
                 ('candidate_inventory',5),('observed_prefix_start',1),('qualified_batch',True),('operator_id','')]
        for key,value in changes:
            with self.subTest(key=key):
                modified=copy.deepcopy(data);modified[key]=value;self.write(path,modified)
                with self.assertRaises(ValueError):self.load(path)
        modified=copy.deepcopy(data);modified['results'][0]['fingerprint']=self.candidates[1]['fingerprint'];self.write(path,modified)
        with self.assertRaises(ValueError):self.load(path)

    def test_raw_hash_classification_counts_and_driver_summary_cannot_be_pasted(self):
        path,data=self.report('raw')
        for field,value in [('status','killed'),('counts',{'tests':2}),('driver_summary',{}),('compile_status','compile_failure'),
                            ('test_timed_out',None),('compile_exit',None)]:
            with self.subTest(field=field):
                changed=copy.deepcopy(data);changed['results'][0][field]=value;self.write(path,changed)
                with self.assertRaises(ValueError):self.load(path)
        self.write(path,data);self.write(path.parent/'0000.test.log','different bytes')
        with self.assertRaises(ValueError):self.load(path)

    def test_warnings_and_compile_failure_are_not_relabelled_kills(self):
        path,data=self.report('compile')
        row=data['results'][0]
        for key in list(row):
            if key.startswith('test_') and key!='test_plan' or key in {'counts','driver_summary'}:del row[key]
        row.update(phase='compile',status='compile_failure',compile_status='compile_failure',compile_exit=1,
                   compile_log_sha256=self.write(path.parent/'0000.compile.log','Build completed. (1 warnings)\n'))
        self.write(path,data);self.assertEqual(self.load(path)['next_index'],1)
        row['status']='killed';self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)

    def test_traversal_symlink_hardlink_alien_owner_and_duplicate_json_refuse(self):
        path,data=self.report('files')
        outside=self.root/'report.json';self.write(outside,data)
        with self.assertRaises(ValueError):self.load(outside)
        link=path.parent/'linked';link.symlink_to(path)
        with self.assertRaises(ValueError):lineage._document(self.campaign,link)
        hard=path.parent/'hard';os.link(path,hard)
        with self.assertRaises(ValueError):self.load(path)
        hard.unlink()
        self.write(path,'{"source_sha":"x","source_sha":"y"}')
        with self.assertRaises(ValueError):self.load(path)
        self.write(path,data)
        with mock.patch.object(lineage.os,'getuid',return_value=os.getuid()+1):
            with self.assertRaises(ValueError):self.load(path)
        with mock.patch.object(lineage,'MAX_DOCUMENT',1):
            with self.assertRaises(ValueError):self.load(path)

    def test_incomplete_unknown_status_skipped_row_and_execution_after_abort_refuse(self):
        path,data=self.report('incomplete',statuses=('timeout','survived'))
        with self.assertRaises(ValueError):self.load(path)
        path,data=self.report('unknown')
        data['results'][0]['status']='unverified';self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)
        path,data=self.report('skip')
        data['observed_prefix_start']=1;data['execution_inventory']=3;data['results'][0].update(self.candidates[1]);self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)

    def test_ack_binds_last_abort_and_declares_distinct_operational_inspection(self):
        path,_=self.report('abort',statuses=('survived','timeout'));history=self.load(path)
        ack_path,data,ack=self.ack(history)
        self.assertEqual(ack['document']['inspection_author'],'inspector-two')
        for field,value in [('inspection_author','worker-one'),('inspection_author',' worker-one '),
                            ('acknowledged_ns',100),('acknowledger_pid',0),
                            ('inspection_note','too short'),('attestation','cryptographic independence')]:
            with self.subTest(field=field):
                modified=copy.deepcopy(data);modified[field]=value;self.write(ack_path,modified)
                with self.assertRaises(ValueError):lineage.validate_ack(self.campaign,ack_path,history)
        data['marker']['logfile']=str(path.parent/'0000.test.log');self.write(ack_path,data)
        with self.assertRaises(ValueError):lineage.validate_ack(self.campaign,ack_path,history)

    def test_restoration_requires_actual_clean_raw_logs_custody_and_after_ack(self):
        path,_=self.report('abort',statuses=('timeout',));history=self.load(path)
        _,_,ack=self.ack(history);rest_path,data,_=self.restoration(history,ack)
        for field,value in [('started_ns',200),('producer',{}),('warnings',['warning']),('timed_out',True),('prior_report',{})]:
            changed=copy.deepcopy(data);changed[field]=value;self.write(rest_path,changed)
            with self.assertRaises(ValueError):lineage.validate_restoration(self.campaign,rest_path,history=history,
                ack=ack,candidates=self.candidates,selection=self.selection,producer=self.producer,harness=self.harness)
        self.write(rest_path,data)
        self.write(rest_path.parent/'restore.cljs.test.log',self.output({**self.summary,'errors':1}))
        with self.assertRaises(ValueError):lineage.validate_restoration(self.campaign,rest_path,history=history,
            ack=ack,candidates=self.candidates,selection=self.selection,producer=self.producer,harness=self.harness)

    def test_real_ancestor_chain_preserves_negatives_and_rejects_reference_drift(self):
        first,_=self.report('first',statuses=('survived','timeout'));parent=self.load(first)
        _,_,ack=self.ack(parent);_,_,restored=self.restoration(parent,ack)
        continuation={'prior_report':{k:parent[k] for k in ('path','sha256','next_index')},
                      'containment_ack':{k:ack[k] for k in ('path','sha256')},
                      'restoration':{k:restored[k] for k in ('path','sha256')}}
        second,data=self.report('second',start=2,statuses=('killed',),continuation=continuation)
        loaded=self.load(second);self.assertEqual(loaded['next_index'],3)
        self.assertEqual(loaded['report_refs'],[continuation['prior_report']])
        data['continuation']['prior_report']['sha256']='0'*64;self.write(second,data)
        with self.assertRaises(ValueError):self.load(second)

    def test_cycles_and_depth_limit_fail_closed(self):
        path,data=self.report('cycle',statuses=('timeout',))
        data['continuation']={'prior_report':{'path':str(path),'sha256':'0'*64,'next_index':0},
                              'containment_ack':{},'restoration':{}}
        self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)
        self.write(path,{**data,'continuation':None})
        with mock.patch.object(lineage,'MAX_ANCESTORS',0):
            with self.assertRaises(ValueError):self.load(path)

    def test_complete_claim_requires_full_selection_and_clean_terminal_restoration(self):
        path,data=self.report('complete',statuses=('survived',)*4)
        data.pop('restored_baseline');data.pop('restored_baselines');self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)
        data['restored_baseline']='passed';data['restored_baselines']={'cljs':self.baseline(path.parent,'restored')}
        self.write(path,data);self.assertEqual(self.load(path)['next_index'],4)

    def test_json_boolean_counters_and_offsets_are_not_integer_evidence(self):
        path,data=self.report('boolean')
        for change in ('offset','count','baseline'):
            modified=copy.deepcopy(data)
            if change=='offset':modified['results'][0]['char_start']=False
            if change=='count':modified['candidate_inventory']=True
            if change=='baseline':modified['baselines']['cljs']['failures']=False
            self.write(path,modified)
            with self.assertRaises(ValueError):self.load(path)

    def test_warning_bearing_baseline_and_changed_restored_counts_refuse(self):
        path,data=self.report('warnings')
        warned=copy.deepcopy(self.summary);warned['warnings']=['native warning'];warned['groups'][0]['warnings']=['native warning']
        data['baselines']['cljs'].update(driver_summary=warned,
            test_log_sha256=self.write(path.parent/'baseline.cljs.test.log',self.output(warned)))
        self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)
        path,data=self.report('restored-counts')
        data['restored_baseline']='passed';data['restored_baselines']={'cljs':self.baseline(path.parent,'restored')}
        data['restored_baselines']['cljs']['assertions']=999;self.write(path,data)
        with self.assertRaises(ValueError):self.load(path)

    def test_multiple_real_ancestors_enforce_cumulative_offsets_and_depth_bound(self):
        first,_=self.report('ancestor-one',statuses=('timeout',));parent=self.load(first)
        def extend(parent,name,status):
            _,_,ack=self.ack(parent,'containment-ack-'+name+'.json')
            _,_,restored=self.restoration(parent,ack,'restore-'+name)
            continuation={'prior_report':{k:parent[k] for k in ('path','sha256','next_index')},
                          'containment_ack':{k:ack[k] for k in ('path','sha256')},
                          'restoration':{k:restored[k] for k in ('path','sha256')}}
            return self.report(name,start=parent['next_index'],statuses=(status,),continuation=continuation)[0]
        second=extend(parent,'ancestor-two','timeout');parent=self.load(second)
        third=extend(parent,'ancestor-three','survived');history=self.load(third)
        self.assertEqual(history['next_index'],3);self.assertEqual(len(history['report_refs']),2)
        with mock.patch.object(lineage,'MAX_ANCESTORS',2):
            with self.assertRaises(ValueError):self.load(third)

    def test_restoration_allows_native_output_randomness_and_assertion_branch_variance(self):
        path,_=self.report('variable-baseline',statuses=('timeout',));history=self.load(path)
        _,_,ack=self.ack(history);receipt,data,_=self.restoration(history,ack,'variable-restore')
        summary=copy.deepcopy(self.summary)
        summary['assertions']=summary['groups'][0]['assertions']=5
        summary['groups'][0]['child_output_sha256']='9'*64
        row=data['baselines']['cljs'];row['driver_summary']=summary;row['assertions']=5
        row['test_log_sha256']=self.write(receipt.parent/'restore.cljs.test.log',self.output(summary))
        self.write(receipt,data)
        result=lineage.validate_restoration(self.campaign,receipt,history=history,ack=ack,
            candidates=self.candidates,selection=self.selection,producer=self.producer,harness=self.harness)
        self.assertEqual(result['document']['baselines']['cljs']['assertions'],5)


if __name__=='__main__':unittest.main()
