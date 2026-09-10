import os
import re

slop_patterns = [
    'pivotal', 'testament', 'evolving landscape', 'setting the stage', 'indelible mark', 'deeply rooted',
    'highlighting', 'ensuring', 'reflecting', 'showcasing', 'fostering',
    'nestled', 'vibrant', 'breathtaking', 'groundbreaking', 'renowned', 'stunning', 'must-visit',
    'delve', 'enduring', 'enhance', 'garner', 'interplay', 'intricate', 'landscape', 'showcase', 'tapestry', 'underscore',
    'serves as', 'stands as', 'boasts', 'features', 'in order to', 'due to the fact that',
    'substrate', 'wedge', 'vector', 'locus', 'vantage', 'nexus', 'primitive', 'harness', 'bedrock', 'scaffolding', 'modality', 'paradigm', 'gold-plating', 'ratchet', 'evacuate', 'endgame', 'north star', 'flywheel',
    'utilize', 'leverage', 'facilitate', 'numerous'
]

def find_comments(filepath):
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        content = f.read()
    
    comments = []
    # match // comments and /* */ comments
    for m in re.finditer(r'(//.*?$)|(/\*.*?\*/)', content, re.MULTILINE | re.DOTALL):
        comment = m.group(0)
        comments.append((m.start(), comment))
    return comments

def check_slop(comment):
    lower_comment = comment.lower()
    for pattern in slop_patterns:
        if pattern in lower_comment:
            return pattern
    return None

results = []
for root, _, files in os.walk('src'):
    for file in files:
        if file.endswith('.java'):
            filepath = os.path.join(root, file)
            comments = find_comments(filepath)
            for start, comment in comments:
                match = check_slop(comment)
                if match:
                    results.append(f"{filepath}: Found '{match}' in\n{comment}\n")

for root, _, files in os.walk('core-engine'):
    for file in files:
        if file.endswith('.java'):
            filepath = os.path.join(root, file)
            comments = find_comments(filepath)
            for start, comment in comments:
                match = check_slop(comment)
                if match:
                    results.append(f"{filepath}: Found '{match}' in\n{comment}\n")

with open('slop_report.txt', 'w', encoding='utf-8') as f:
    f.write("\n".join(results))
    
print(f"Found {len(results)} sloppy comments.")
