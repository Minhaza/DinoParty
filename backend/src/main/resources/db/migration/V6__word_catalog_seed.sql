-- WP-01: seed of the built-in English word catalog (version 1). Reproducible: ids are derived
-- from locale and text (md5 -> uuid), so every database gets the same stable ids.
-- Every word of games/drawguess/words-easy.txt is EASY here and every word of words-hard.txt is
-- MEDIUM or HARD (decision D32; checked by WordCatalogSeedIT). Original list, reviewed for
-- offensive entries and drawability (H8).

INSERT INTO word_category (key, sort_order) VALUES
    ('animals', 10),
    ('food-drinks', 20),
    ('nature', 30),
    ('objects', 40),
    ('places', 50),
    ('movies-games', 60),
    ('sports', 70),
    ('technology', 80),
    ('actions', 90);

INSERT INTO word_category_label (category_key, locale, label) VALUES
    ('animals', 'en', 'Animals'),
    ('food-drinks', 'en', 'Food & Drinks'),
    ('nature', 'en', 'Nature'),
    ('objects', 'en', 'Objects'),
    ('places', 'en', 'Places'),
    ('movies-games', 'en', 'Movies & Games'),
    ('sports', 'en', 'Sports'),
    ('technology', 'en', 'Technology'),
    ('actions', 'en', 'Actions');

INSERT INTO word_catalog_entry (id, locale, display_text, category_key, difficulty)
SELECT md5('dinoparty-word:en:' || w.display_text)::uuid, 'en', w.display_text, g.category_key, g.difficulty
FROM (VALUES
    ('animals', 'EASY', ARRAY[
        'cat', 'dog', 'fish', 'bird', 'frog', 'duck', 'cow', 'pig', 'horse', 'sheep', 'goat',
        'mouse', 'rabbit', 'bear', 'lion', 'tiger', 'zebra', 'monkey', 'snake', 'turtle', 'whale',
        'shark', 'octopus', 'crab', 'spider', 'bee', 'ant', 'butterfly', 'snail', 'owl', 'penguin',
        'elephant', 'giraffe', 'camel', 'dinosaur', 'worm', 'ladybug', 'swan', 'parrot', 'flamingo',
        'kangaroo', 'koala', 'panda', 'fox', 'wolf', 'deer', 'squirrel', 'hedgehog', 'seal',
        'dolphin', 'jellyfish', 'starfish', 'tail', 'chicken', 'bat']),
    ('animals', 'MEDIUM', ARRAY[
        'beekeeper', 'peacock', 'porcupine', 'hippopotamus', 'rhinoceros', 'crocodile', 'gorilla',
        'lobster', 'seahorse', 'ostrich', 'beaver', 'raccoon', 'skunk', 'walrus', 'chameleon',
        'caterpillar', 'dragonfly', 'woodpecker', 'llama', 'sloth', 'moose', 'eagle', 'mosquito',
        'scorpion', 'pelican', 'toucan', 'hamster', 'platypus', 'narwhal', 'anteater']),
    ('animals', 'HARD', ARRAY[
        'hibernation', 'migration', 'camouflage', 'metamorphosis', 'bookworm', 'night owl',
        'early bird', 'copycat', 'school of fish', 'echolocation', 'food chain']),
    ('food-drinks', 'EASY', ARRAY[
        'apple', 'banana', 'cake', 'pizza', 'egg', 'bread', 'cheese', 'milk', 'cookie', 'ice cream',
        'hot dog', 'popcorn', 'carrot', 'tomato', 'potato', 'corn', 'grapes', 'lemon', 'cherry',
        'pear', 'orange', 'watermelon', 'strawberry', 'pumpkin', 'mushroom', 'sandwich', 'donut',
        'pancake', 'honey', 'lollipop', 'onion']),
    ('food-drinks', 'MEDIUM', ARRAY[
        'chef', 'avocado', 'broccoli', 'cauliflower', 'pineapple', 'coconut', 'dumpling',
        'spaghetti', 'sushi', 'burrito', 'croissant', 'pretzel', 'waffle', 'milkshake', 'lemonade',
        'wedding cake', 'cupcake', 'hamburger', 'french fries', 'taco', 'noodles', 'cereal',
        'salad', 'omelette', 'hot chocolate', 'gingerbread man', 'candy cane', 'ice cube', 'coffee',
        'barbecue']),
    ('food-drinks', 'HARD', ARRAY[
        'artichoke', 'lasagna', 'smoothie', 'picnic', 'food truck', 'fortune cookie', 'recipe',
        'leftovers', 'breakfast in bed', 'salt and pepper']),
    ('nature', 'EASY', ARRAY[
        'baby', 'tree', 'sun', 'moon', 'star', 'rain', 'snow', 'cloud', 'rainbow', 'river',
        'mountain', 'island', 'wave', 'leaf', 'grass', 'rose', 'flower', 'cactus', 'palm tree',
        'volcano', 'forest', 'lake', 'planet', 'nest', 'feather', 'shell', 'bone', 'tooth', 'nose',
        'eye', 'ear', 'hand', 'foot', 'heart', 'snowman']),
    ('nature', 'MEDIUM', ARRAY[
        'lightning', 'tornado', 'fossil', 'glacier', 'iceberg', 'canyon', 'swamp', 'jungle',
        'oasis', 'coral reef', 'sunburn', 'campfire', 'skeleton', 'lumberjack', 'harvest',
        'scarecrow', 'waterfall', 'sunflower', 'snowflake', 'pinecone', 'puddle', 'sunset', 'acorn',
        'bamboo', 'dandelion', 'spider web', 'footprint', 'comet']),
    ('nature', 'HARD', ARRAY[
        'avalanche', 'earthquake', 'eclipse', 'constellation', 'geyser', 'mirage', 'quicksand',
        'stalactite', 'goosebumps', 'compost', 'recycling', 'archaeologist', 'gravity',
        'solar system', 'tsunami', 'drought', 'sandstorm', 'northern lights', 'evaporation']),
    ('objects', 'EASY', ARRAY[
        'chair', 'table', 'cup', 'hat', 'shoe', 'sock', 'door', 'window', 'book', 'candle', 'clock',
        'key', 'lamp', 'bed', 'bell', 'box', 'crown', 'ladder', 'hammer', 'pencil', 'scissors',
        'umbrella', 'glasses', 'ring', 'button', 'balloon', 'gift', 'drum', 'guitar', 'piano',
        'trumpet', 'flag', 'map', 'coin', 'basket', 'bucket', 'bottle', 'spoon', 'fork', 'plate',
        'bowl', 'pillow', 'mirror', 'toothbrush', 'soap', 'towel', 'scarf', 'glove', 'boot',
        'jacket', 'dress', 'backpack', 'anchor', 'car', 'boat', 'train', 'bus', 'bicycle',
        'airplane', 'helicopter', 'tractor', 'truck', 'ship']),
    ('objects', 'MEDIUM', ARRAY[
        'compass', 'hourglass', 'thermometer', 'stethoscope', 'wheelchair', 'bandage', 'suitcase',
        'passport', 'postcard', 'piggy bank', 'alarm clock', 'hammock', 'parachute', 'sailboat',
        'cable car', 'hot-air balloon', 'carpenter', 'plumber', 'origami', 'paintbrush', 'teapot',
        'rubber duck', 'safety pin', 'paper clip', 'padlock', 'watering can', 'wheelbarrow',
        'fire hydrant', 'mailbox', 'envelope', 'magnifying glass', 'sewing machine', 'frying pan',
        'rolling pin', 'chopsticks', 'necklace', 'bow tie', 'stapler', 'toolbox', 'screwdriver',
        'lawn mower', 'motorcycle', 'scooter', 'snow globe', 'wind chime', 'music box',
        'rocking chair']),
    ('objects', 'HARD', ARRAY[
        'souvenir', 'blueprint', 'family tree', 'sundial', 'kaleidoscope', 'pottery', 'sculpture',
        'deadline', 'message in a bottle', 'time capsule', 'dreamcatcher', 'jack-in-the-box',
        'grandfather clock', 'chandelier']),
    ('places', 'EASY', ARRAY[
        'house', 'castle', 'bridge', 'tent', 'igloo', 'beach', 'lighthouse', 'sandcastle', 'farm',
        'school', 'park', 'zoo', 'garden', 'cave', 'desert', 'barn', 'playground']),
    ('places', 'MEDIUM', ARRAY[
        'skyscraper', 'windmill', 'haunted house', 'museum', 'greenhouse', 'tunnel', 'highway',
        'crosswalk', 'traffic light', 'pyramid', 'totem pole', 'drawbridge', 'shipwreck',
        'roller coaster', 'ferris wheel', 'merry-go-round', 'librarian', 'firefighter', 'hospital',
        'library', 'airport', 'train station', 'bakery', 'restaurant', 'supermarket', 'treehouse',
        'bus stop', 'harbor', 'palace', 'aquarium']),
    ('places', 'HARD', ARRAY[
        'roundabout', 'sphinx', 'construction site', 'waiting room', 'lost and found',
        'city skyline', 'ghost town', 'traffic jam', 'laundromat', 'observatory']),
    ('movies-games', 'EASY', ARRAY[
        'ghost', 'dragon', 'unicorn', 'mermaid', 'alien', 'pirate', 'wizard', 'king', 'queen',
        'kite', 'dice', 'teddy bear', 'doll', 'clown', 'monster', 'superhero', 'puppet', 'princess']),
    ('movies-games', 'MEDIUM', ARRAY[
        'juggler', 'acrobat', 'magician', 'knight', 'jester', 'detective', 'chess', 'domino',
        'jigsaw puzzle', 'yo-yo', 'boomerang', 'pogo stick', 'stilts', 'karaoke', 'ballet',
        'orchestra', 'magic trick', 'fireworks', 'birthday party', 'hide-and-seek', 'hopscotch',
        'red carpet', 'tightrope', 'crystal ball', 'treasure map', 'treasure chest', 'video game',
        'board game', 'game controller', 'playing card', 'movie theater', 'zombie', 'comic book',
        'magic wand', 'tic-tac-toe', 'checkers', 'ticket']),
    ('movies-games', 'HARD', ARRAY[
        'ventriloquist', 'conductor', 'autograph', 'applause', 'standing ovation', 'fortune teller',
        'cliffhanger', 'checkmate', 'clapperboard', 'cartoon', 'high score', 'game over',
        'level up', 'rock paper scissors', 'treasure hunt', 'stunt double', 'escape room']),
    ('sports', 'EASY', ARRAY[
        'ball', 'medal', 'trophy', 'whistle', 'helmet', 'basketball', 'baseball', 'soccer',
        'tennis', 'golf', 'bowling', 'skiing']),
    ('sports', 'MEDIUM', ARRAY[
        'referee', 'goalkeeper', 'lifeguard', 'skateboard', 'snowboard', 'surfboard', 'scuba diver',
        'snorkel', 'kayak', 'unicycle', 'tug-of-war', 'trampoline', 'boxing glove', 'ice skating',
        'volleyball', 'badminton', 'hockey', 'archery', 'karate', 'fishing rod', 'dumbbell', 'yoga',
        'finish line', 'high jump', 'ping pong', 'cheerleader', 'jump rope', 'scoreboard',
        'race car']),
    ('sports', 'HARD', ARRAY[
        'marathon', 'teamwork', 'photo finish', 'penalty kick', 'relay race', 'home run',
        'slam dunk', 'halftime', 'underdog', 'hat trick']),
    ('technology', 'EASY', ARRAY[
        'robot', 'rocket', 'phone', 'computer', 'television', 'camera', 'radio', 'laptop']),
    ('technology', 'MEDIUM', ARRAY[
        'astronaut', 'scientist', 'telescope', 'microscope', 'satellite', 'submarine', 'battery',
        'light bulb', 'keyboard', 'headphones', 'microphone', 'loudspeaker', 'vending machine',
        'escalator', 'elevator', 'selfie', 'magnet', 'computer mouse', 'remote control', 'printer',
        'drone', 'flashlight', 'calculator', 'solar panel', 'wind turbine', 'satellite dish',
        'spaceship', 'barcode', 'antenna', 'charger']),
    ('technology', 'HARD', ARRAY[
        'electricity', 'password', 'time machine', 'podcast', 'zeppelin', 'virtual reality',
        'circuit board', 'power outage', 'search engine', 'screenshot', 'firewall', 'download']),
    ('actions', 'EASY', ARRAY[
        'smile', 'jump', 'run', 'swim', 'sleep', 'cry', 'laugh', 'dance', 'sing', 'eat', 'kick',
        'clap', 'climb', 'fly', 'sit']),
    ('actions', 'MEDIUM', ARRAY[
        'sneeze', 'yawn', 'whisper', 'hug', 'wink', 'shiver', 'brushing teeth', 'tiptoe',
        'knitting', 'painting', 'snoring', 'sweeping', 'stretching', 'sneaking', 'shopping',
        'baking', 'high five', 'blowing bubbles', 'digging', 'sunbathing', 'typing', 'vacuuming']),
    ('actions', 'HARD', ARRAY[
        'sleepwalking', 'brainstorm', 'daydream', 'nightmare', 'homesick', 'jet lag', 'hiccups',
        'couch potato', 'eavesdropping', 'photobomb', 'moonwalk', 'making a wish', 'stage fright'])
) AS g (category_key, difficulty, words)
CROSS JOIN LATERAL unnest(g.words) AS w (display_text);
