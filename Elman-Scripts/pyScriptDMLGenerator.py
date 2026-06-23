lengths = [5, 10, 15, 20, 30, 50, 100]

for n in lengths:
    with open(f"Chain_{n}.dml", "w") as f:
        # Generate N matrices
        for i in range(1, n + 1):
            f.write(f"M{i} = rand(rows=10, cols=10, sparsity=1.0)\n")
        
        # Build the multiplication chain string
        chain = " %*% ".join([f"M{i}" for i in range(1, n + 1)])
        
        # Add a random transpose in the middle to trigger the rewrite
        mid = n // 2
        chain = chain.replace(f"M{mid}", f"t(M{mid})")
        
        f.write(f"\nR = {chain}\n")
        f.write('write(R, "output.csv")\n')