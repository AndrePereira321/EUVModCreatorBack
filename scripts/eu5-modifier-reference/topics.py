"""Sort modifiers into topics by the words in their key.

topic_rules(goods, estates, pops) -> topic_of(key, ai) returning a topic name.
The first matching rule wins, so the order below matters: a goods key like
ban_exports_of_wine is Trade goods, not Economy.
"""
import re

OTHER = 'Other'


def words(*ws):
    return r'(?:^|_)(?:' + '|'.join(sorted(ws, key=len, reverse=True)) + r')(?:_|$)'


def topic_rules(goods, estates, pops):
    rules = [
        ('AI behaviour', words('ai', 'aggressiveness', 'carefulness', 'importance', 'preference', 'reasons',
                               'correct_box')),
        ('Trade goods', words(*goods)),
        ('Estates', words('estate', 'estates', *estates)),
        ('Societal values', words('towards', 'societal')),
        ('Navy', words('navy', 'naval', 'ship', 'ships', 'sea', 'galley', 'galleys', 'sailors', 'admiral', 'blockade',
                       'port', 'flagship', 'transport', 'transports', 'boarding', 'privateer', 'privateers',
                       'privateering', 'maritime', 'piracy', 'pirate', 'shipwright', 'treasure') + '|^n_'),
        ('Army', words('army', 'regiment', 'regiments', 'infantry', 'cavalry', 'artillery', 'levy', 'levies',
                       'mercenary', 'mercenaries', 'morale', 'war', 'fort', 'forts', 'siege', 'manpower', 'general',
                       'generals', 'discipline', 'combat', 'reinforce', 'military', 'land', 'unit', 'units',
                       'attrition', 'garrison', 'retinue', 'auxiliary', 'flank', 'damage', 'battle', 'occupation',
                       'drill', 'supply', 'cb', 'warscore', 'fortification', 'fortifications', 'assault', 'combined',
                       'frontage', 'retreat', 'looted', 'defensive', 'cataphracts', 'legionaries', 'conquistadors',
                       'warrior', 'barracks', 'commanders', 'leader', 'leaders', 'experience') + '|^a_'),
        ('Pops & society', words('pop', 'pops', *pops, 'migration', 'assimilation', 'culture', 'cultures', 'cultural',
                                 'language', 'literacy', 'population', 'growth', 'food', 'tolerance', 'slave',
                                 'slavery', 'tribal', 'tribals', 'tribe', 'dialect', 'disease', 'plague', 'influenza',
                                 'malaria', 'measles', 'smallpox', 'typhus', 'pest', 'upper', 'class',
                                 'enfranchisment', 'separatism')),
        ('Religion', words('religious', 'religion', 'religions', 'holy', 'piety', 'church', 'papal', 'faith',
                           'conversion', 'saint', 'karma', 'heresy', 'heretic', 'clergy', 'missionary',
                           'missionaries', 'devotion', 'zeal', 'theocracy', 'doom', 'purity', 'righteousness',
                           'mandate', 'yin', 'yang', 'dhimmi', 'jihad', 'crusade', 'god', 'gods', 'reformation',
                           'hellenism', 'harmony', 'spirit', 'ancestor', 'hindu', 'buddhist', 'buddhism', 'orthodox',
                           'catholic', 'curia', 'pope', 'cardinal', 'cardinals', 'excommunication', 'canonization',
                           'omen', 'omens', 'rite', 'monasteries', 'cleric', 'clerical', 'mysticism', 'avatars',
                           'preordained', 'monks', 'theocratic', 'yanantin', 'calvinism', 'lutheranism', 'tithe',
                           'guelphs')),
        ('Diplomacy', words('diplomatic', 'diplomacy', 'diplo', 'opinion', 'trust', 'subject', 'subjects', 'overlord',
                            'vassal', 'vassals', 'alliance', 'alliances', 'ally', 'allies', 'marriage', 'claim',
                            'antagonism', 'reputation', 'annex', 'annexation', 'relations', 'relation', 'diplomats',
                            'diplomat', 'rival', 'rivals', 'peace', 'truce', 'envoy', 'envoys', 'spy', 'spies',
                            'espionage', 'casus', 'aggressive', 'coalition', 'liberty', 'hre', 'imperial', 'emperor',
                            'elector', 'electors', 'electorship', 'international', 'organization', 'hegemon',
                            'hegemony', 'tributary', 'tributaries', 'tribute', 'great', 'prestige', 'bias',
                            'subjugation', 'subjugated', 'embargo', 'sovereignty', 'guarantee', 'influence', 'union',
                            'border', 'kingship', 'senior', 'projection', 'formation', 'forming')),
        ('Exploration & colonies', words('colonial', 'colony', 'colonies', 'colonist', 'colonists', 'colonize',
                                         'explore', 'exploration', 'explorer', 'explorers', 'settlers', 'settler',
                                         'expedition', 'expeditions', 'charter', 'charters', 'discovery',
                                         'tordesillas', 'wasteland')),
        ('Characters & court', words('character', 'characters', 'spouses', 'lowborn', 'female', 'male', 'heir',
                                     'heirs', 'marrying', 'artist', 'artists', 'art', 'life', 'mortality', 'education',
                                     'beards', 'eunuch', 'ruler', 'rulers', 'court', 'advisor', 'advisors',
                                     'olympiads', 'apprenticeships', 'gender', 'coa', 'governors', 'governorship',
                                     'inspectors')),
        ('Government', words('cabinet', 'parliament', 'parliaments', 'law', 'laws', 'policy', 'policies', 'reform',
                             'reforms', 'bureaucracy', 'crown', 'legitimacy', 'stability', 'council', 'succession',
                             'regency', 'control', 'government', 'agenda', 'agendas', 'privilege', 'privileges',
                             'administrative', 'centralization', 'rebel', 'rebels', 'unrest', 'revolt', 'corruption',
                             'capital', 'proximity', 'integration', 'votes', 'vote', 'parliamentary', 'monarch',
                             'republican', 'tradition', 'traditions', 'merit', 'authority', 'absolutism', 'rank',
                             'ranks', 'decree', 'decrees', 'action', 'actions', 'legislative', 'election', 'elect',
                             'bailiffs', 'lieutenancy', 'administrations', 'ambition', 'political', 'honor', 'unity',
                             'horde', 'taxation', 'chancery', 'landfriede', 'ostrogs', 'banish', 'execute', 'panaqas',
                             'delegation')),
        ('Research', words('research', 'institution', 'institutions', 'forums', 'advance', 'advances', 'innovation',
                           'archives')),
        ('Economy', words('tax', 'taxes', 'income', 'gold', 'inflation', 'minting', 'loan', 'loans', 'interest',
                          'price', 'prices', 'market', 'markets', 'trade', 'trades', 'trader', 'merchant', 'merchants',
                          'building', 'buildings', 'construction', 'maintenance', 'upkeep', 'debt', 'treasury',
                          'money', 'goods', 'demand', 'production', 'output', 'rgo', 'exports', 'imports', 'export',
                          'import', 'extract', 'establishment', 'industry', 'road', 'roads', 'harbor', 'harbour',
                          'town', 'city', 'cities', 'rights', 'tariff', 'tariffs', 'budget', 'cost', 'costs',
                          'expense', 'expenses', 'bond', 'bonds', 'creditworthiness', 'development', 'prosperity',
                          'devastation', 'selling', 'raw', 'level', 'guild', 'villa', 'headquarters', 'headquarter',
                          'salary', 'sell', 'aqueduct', 'irrigant')),
    ]
    compiled = [(name, re.compile(rx)) for name, rx in rules]

    def topic_of(key, ai=False):
        if ai:
            return 'AI behaviour'
        return next((name for name, rx in compiled if rx.search(key)), OTHER)

    return topic_of
