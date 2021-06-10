from PIL import Image
import cv2
import numpy as np
import sys

image1 = cv2.imread("test2.jpeg")
mask_layer = np.zeros(image1.shape, dtype = "uint8")

frequency1 = 10
frequency0 = 3

waitKey1 = int(1000/frequency1)
waitKey0 = int(1000/frequency0)

frameWindow = 8

message = str(sys.argv[2])
alpha = float(sys.argv[1])

if alpha > 1:
	sys.exit("Alpha must be decimal value between 0 and 1.")

image2 = cv2.addWeighted(image1, 1-alpha, mask_layer, alpha, 0)
count = 0

for i in range(len(message)):
	for j in range(frameWindow):
		if count%2 == 0:
			cv2.imshow("Transmitter", image1)
		else:
			cv2.imshow("Transmitter", image2)
		count += 1

		if str(message[i]) == "1":
			cv2.waitKey(waitKey1)
		else:
			cv2.waitKey(waitKey0)