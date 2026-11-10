"""Fixed Work campaign selection and credential-free worker entrypoint."""
import argparse
from pathlib import Path
import signal

APPLICATION = '4805e9aa1ab574c2e517f6c156bca0c1e69696fb'
SCANNER = '87c6d75c386afbeac1731941c535e6a16fcc05c4'
SCANNER_HEAD = '5b893813530d2bf49a63e2cf24ab4d5cfc3f2beb'
SCANNER_PR = 53
APPLICATION_PR = 52
WORKFLOW = '.github/workflows/qualification-work-campaign.yml'
PROFILE = 'standard-v2'
SEED = 'full-qualification-4805e9a-v1'
INVENTORY = '7f77f0d5c74a6bedd2baf793489a3cc47876c2870c99a5863004660cca18c698'
PARTITIONS = {
    0: (1562, 'cbffaac6e29375406b37d76b14e08f8ba6abcffb930982ff339bbbd60a8a8742'),
    1: (1582, 'bd55eb9b61c039daca6121325844918263538a798e0c4d8aff888808230f2266'),
    2: (1595, '447c882d046d07a72b0c9afff7477a59a134a7407bc061a2bea19720b4b864d0'),
    3: (1603, '20878ab7a773969cb66e2a0ed2b36183f04da238626e7cd93e2d9a616d6a6862'),
}
OUTPUT = 'work-standard-v2-v1'
ENTRYPOINT = 'qualification/work_campaign.py'


def select(campaign, lineage, partition):
    if campaign.SOURCE_SHA != APPLICATION or type(partition) is not int or partition not in PARTITIONS:
        raise ValueError('The exact Work application and a fixed partition are required')
    candidates, selection = lineage.select_candidates(campaign, profile=PROFILE, path_prefix='src/cljs/',
                                                       batch_index=partition, sample_seed=SEED)
    if ((len(candidates), selection['selection_sha256']) != PARTITIONS[partition]
            or selection['inventory_sha256'] != INVENTORY
            or len(selection['inventory_fingerprints']) != sum(count for count, _ in PARTITIONS.values())):
        raise ValueError('The complete Work selection differs from its frozen input')
    return candidates, selection


def main():
    import collect_mutation_evidence as collector

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--partition', type=int, choices=PARTITIONS, required=True)
    partition = parser.parse_args().partition
    campaign = collector.campaign
    producer = campaign.SOURCE_SHA  # Imported from the trusted native controller checkout.
    root = Path.cwd()
    if root == campaign.ROOT:
        raise ValueError('The Work producer needs a separate exact application checkout')
    signal.signal(signal.SIGINT, campaign.interrupted)
    signal.signal(signal.SIGTERM, campaign.interrupted)
    signal.signal(signal.SIGUSR1, campaign.stop_after_current)
    with collector.worker_context(root, work=True), campaign.campaign_lock():
        campaign.require_unchanged_tree()
        campaign.require_no_containment_marker()
        if list((root / 'qualification/results').rglob('inflight.json')):
            raise ValueError('The Work checkout retains an interrupted mutation')
        candidates, selection = select(campaign, collector.lineage, partition)
        campaign.run_campaign(candidates, root / 'qualification/results' / OUTPUT, len(candidates), 300,
                              partition, SEED, APPLICATION, producer, selection=selection,
                              operator_id='github-native/work/partition' + str(partition))


if __name__ == '__main__':
    main()
